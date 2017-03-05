package com.r4intellij.inspections;

import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.util.PsiTreeUtil;
import com.r4intellij.RPsiUtils;
import com.r4intellij.editor.RCompletionContributor;
import com.r4intellij.intentions.ImportLibraryFix;
import com.r4intellij.packages.RPackage;
import com.r4intellij.packages.RPackageService;
import com.r4intellij.psi.api.*;
import com.r4intellij.psi.references.RReferenceImpl;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.r4intellij.inspections.TypeCheckerInspection.isPipeContext;

public class UnresolvedReferenceInspection extends RInspection {


    @Nls
    @NotNull
    @Override
    public String getDisplayName() {
        return "Unresolved reference";
    }


    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly, @NotNull LocalInspectionToolSession session) {
        return new ReferenceVisitor(holder);
    }


    private class ReferenceVisitor extends RVisitor {

        private final ProblemsHolder myProblemHolder;


        public ReferenceVisitor(@NotNull ProblemsHolder holder) {
            myProblemHolder = holder;
        }


        @Override
        public void visitReferenceExpression(@NotNull RReferenceExpression element) {
            // how does/should it work:
            // exclude (ie. white-list) certain situations to show up as unresolved, but generally use
            // Ref.resolve() to check for resolvability


            if (RPsiUtils.isNamedArgument(element)) {
                return;
            }


            // don't tag initial declarations of variables as unresolvable
            if (RPsiUtils.isVarDeclaration(element)) {
                return;
            }

            // ignore function calls here because they are handled by the missing import inspection
            if (element.getParent() instanceof RCallExpression) {
                if (resolveInPackages((RCallExpression) element.getParent(), myProblemHolder)) {
                    // we could find it in a package or locally so it's somehow resolvable but not yet imported
                    return;
                }
            }


            RCallExpression callExpression = PsiTreeUtil.getParentOfType(element, RCallExpression.class);

//            // this is type-checker stuff and should be handled there ## todo remove code bits once test are running
//            // ignore named arguments in tripledot calls. e.g. myfun=function(a, ...) a; myfun(23, b=4)
//            if (callExpression != null) {
//                RFunctionExpression function = RPsiUtils.getFunction(callExpression);
//                if (function != null) {
//                    List<RParameter> list = function.getParameterList().getParameterList();
//                    if (RPsiUtils.containsTripleDot(list)) {
//                        return;
//                    }
//                }
//            }


            // prevent package names to show up as unresolved, because they are handled separately by
            // the missing package inspection
            if (callExpression != null &&
                    RCompletionContributor.PACKAGE_IMPORT_METHODS.contains(callExpression.getExpression().getName())) {
                return;
            }

            // prevent package names to show up as unresolved in packageName-calls, because they are handled separately by
            // the missing package inspection
            if (RPsiUtils.isNamespacePrefix(element)) return;


            // exclude white-listed argument positions containing unquoted variable names
            List<String> whiteList = Arrays.asList("dplyr::count(2)", "dplyr::count(2-)");
            boolean isFirstArgInjected = isPipeContext(callExpression);

//            new ArgumentMatcher().matchArgs();



            // resolve normally
            RReferenceImpl reference = element.getReference();

            if (reference != null) {
                PsiElement resolve = reference.resolve(true);

                if (resolve == null) {

                    myProblemHolder.registerProblem(element, "Unresolved reference", ProblemHighlightType.GENERIC_ERROR_OR_WARNING);
                } else if (RPsiUtils.isForwardReference(resolve, element)) {
//                    // try to find forward references in file
//                    FileContextResolver fileContextResolver = new FileContextResolver();
//                    fileContextResolver.setForwardRefs(true);
//
//                    List<ResolveResult> forwardRefs = fileContextResolver.resolveFromInner(element, element, element.getName());
//
//                    if(!forwardRefs.isEmpty()) {
                    myProblemHolder.registerProblem(element, "Forward reference", ProblemHighlightType.GENERIC_ERROR);
//                    }
                }
            }
        }


        // todo do once all current unit-tests are fixed:
        // todo thise should go into the RResolver and complement or replace com.r4intellij.psi.references.RResolver.resolveFunction*() !!
        private boolean resolveInPackages(RCallExpression psiElement, ProblemsHolder problemsHolder) {
            // is it a locally defined function?
            RFunctionExpression function = RPsiUtils.getFunction(psiElement);

            boolean isLocalFunction = function != null && function.getContainingFile().equals(psiElement.getContainingFile());
            if (isLocalFunction) {
                return true;
            }

            RPackageService packageService = RPackageService.getInstance();
//            if (!packageService.isReady()) return true; // fixme!

            // search packages by function name
            RExpression funExpr = psiElement.getExpression();
            String functionName = funExpr.getText();

            List<RPackage> contPackages = packageService.getContainingPackages(functionName);
            List<String> contPackageNames = Lists.newArrayList(Iterables.transform(contPackages, Functions.toStringFunction()));

            if (contPackageNames.isEmpty())
                return false;

            // check if there's an import statement for any of them

            List<RPackage> resolvedImports = packageService.resolveImports(psiElement);
            List<String> importedPackages = Lists.newArrayList(Iterables.transform(resolvedImports, Functions.toStringFunction()));

            // check whether the import list contains any of the packages
            if (!Sets.intersection(Sets.newHashSet(importedPackages), Sets.newHashSet(contPackageNames)).isEmpty()) {
                return true;
            }

            // no overlap --> highlight as error and suggest to import one!

            // Also provide importing packages as options (like tidyverse for mutate)
            // DISABLED: works but is adding too many confusing options
//                    Set<String> funPckgByImport = funPackageNames.stream().
//                            flatMap(pName -> packageService.getImporting(packageService.getByName(pName)).stream()).
//                            map(RPackage::getName).
//                            collect(Collectors.toSet());
//                    funPackageNames.addAll(funPckgByImport);


            List<LocalQuickFix> fixes = new ArrayList<LocalQuickFix>();
            for (String funPackageName : contPackageNames) {
                fixes.add(new ImportLibraryFix(funPackageName));
            }

            String descriptionTemplate = "'" + functionName + "' has been detected in a package (" +
                    Joiner.on(", ").join(contPackageNames) + ") which does not seem to be imported yet.";
            problemsHolder.registerProblem(funExpr, descriptionTemplate, fixes.toArray(new LocalQuickFix[0]));

            return true;
        }

    }
}
