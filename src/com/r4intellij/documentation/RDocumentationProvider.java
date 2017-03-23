package com.r4intellij.documentation;

import com.google.common.base.CharMatcher;
import com.google.common.base.Strings;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.openapi.editor.Editor;
import com.intellij.patterns.PsiElementPattern;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.r4intellij.RFileType;
import com.r4intellij.psi.RElementFactory;
import com.r4intellij.psi.RReferenceExpressionImpl;
import com.r4intellij.psi.api.*;
import com.r4intellij.psi.references.RResolver;
import com.r4intellij.settings.RSettings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;

import static com.intellij.patterns.PlatformPatterns.psiElement;
import static com.r4intellij.interpreter.RSkeletonGenerator.SKELETON_DIR_NAME;
import static com.r4intellij.packages.RHelperUtil.LOG;

/**
 * For local function definitions provide doc string documentation (using docstring)
 * For library functions use R help.
 */
public class RDocumentationProvider extends AbstractDocumentationProvider {

    private static Integer HELP_SERVER_PORT;


    @Nullable
    @Override
    public PsiElement getCustomDocumentationElement(@NotNull Editor editor, @NotNull PsiFile file, @Nullable PsiElement contextElement) {
        List<String> keywords = Arrays.asList("for", "while", "next", "break", "function");
        if (contextElement != null && keywords.contains(contextElement.getText())) {
            return contextElement;
        }

        return null;
    }


    @Override
    public List<String> getUrlFor(PsiElement element, PsiElement originalElement) {
        URL restoredURL = restoreInterceptedLink(element);
        if (restoredURL != null) {
            return Arrays.asList(restoredURL.toString());
        }

        String elPckgage = detectPackage(element);

        if (elPckgage == null) {
            return new ArrayList<>();
        }

        String symbol;
        if (originalElement != null) {
            symbol = originalElement.getText();
        } else { // this applies just when help links are clicked
            symbol = element.getText();
        }

        return Arrays.asList("http://127.0.0.1:" + HELP_SERVER_PORT + "/library/" + elPckgage + "/html/" + symbol + ".html");

        // build link to local help
//        return Arrays.asList("http://www.heise.de");
    }


    private static void ensureHelpServerAlive() {
        // check if help server is alive and restart it if necessary
        try {
            new Scanner(new URL("http://127.0.0.1:" + HELP_SERVER_PORT)
                    .openStream(), "UTF-8").useDelimiter("\\A").next();
        } catch (IOException e) {
            // if server is down or unresponsive, restart it under a different port
//            e.printStackTrace();
            HELP_SERVER_PORT = null;
            startHelpServer(null);
        }


        // wait until it's ready (at least for a while
        for (int i = 0; i < 5; i++) {
            if (HELP_SERVER_PORT != null) break;
            sleep(200);
        }
    }


    @Nullable
    @Override
    public String generateDoc(PsiElement reference, @Nullable PsiElement identifier) {

        if (!(RFileType.INSTANCE.equals(reference.getContainingFile().getFileType()))) return null;
//        if (!(psiFile instanceof RFile)) {


        // wait until help server is ready (do it here since we need the port to build the URL)
        ensureHelpServerAlive();

        // check if doc of internally rerouted doc-popup click
        URL restoredURL = restoreInterceptedLink(reference);
        if (restoredURL != null) {
            return getHelpFromLocalHelpServer(restoredURL);
        }

        if (reference instanceof RStringLiteralExpression) return null;
        // check if it's a library function and return help if it is


//        if (identifier == null) return null;
        // not identifier is null when
        if (identifier == null) identifier = reference;

        String elementText = identifier.getText();
        if (elementText.trim().isEmpty()) return null;


        // first guess : process locally defined function definitions
        if (!isLibraryElement(reference)) {
            if (reference instanceof RAssignmentStatement) {
                RPsiElement assignedValue = ((RAssignmentStatement) reference).getAssignedValue();

                if (assignedValue instanceof RFunctionExpression) {
                    String docString = ((RFunctionExpression) assignedValue).getDocStringValue();
                    return docString != null ? docString : "No doc-string found for locally defined function.";
                }
            }
        }

        String packageName = detectPackage(reference);


        URL localHelpURL;

        assert HELP_SERVER_PORT != null;

        if (packageName != null) {
            localHelpURL = makeURL("http://127.0.0.1:" + HELP_SERVER_PORT + "/library/" + packageName + "/help/" + elementText);
        } else {
            localHelpURL = makeURL("http://127.0.0.1:" + HELP_SERVER_PORT + "/library/NULL/help/" + elementText);
        }

//        return getHelpForFunction(elementText, packageName);
        return getHelpFromLocalHelpServer(localHelpURL);
    }


    /**
     * Intercepts clicks in documentation popup if link starts with psi_element://
     * <p>
     * See https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000095710-Intercept-clicks-in-documentation-popup-
     *
     * @param psiManager
     * @param link
     * @param context
     * @return
     */
    @Override
    public PsiElement getDocumentationElementForLink(PsiManager psiManager, String link, PsiElement context) {
        PsiElement linkIntercept = RElementFactory.buildRFileFromText(psiManager.getProject(), "help_url(\"" + link + "\")").getFirstChild();

        if (linkIntercept != null) {
            return linkIntercept;
        } else {
            return null;
        }
    }


    @Nullable
    private String getHelpFromLocalHelpServer(URL localHelpURL) {
        try {

            String htmlRaw = new Scanner(localHelpURL.openStream(), "UTF-8").useDelimiter("\\A").next();
            htmlRaw.indexOf("</head><body>");

            String htmlTrimmed = htmlRaw.substring(htmlRaw.indexOf("</head><body>") + 13, htmlRaw.length()).trim();

            // fix relative URLs
//            htmlTrimmed = htmlTrimmed.replace("href=\"../../", "href=\"http://127.0.0.1:" + HELP_SERVER_PORT + "/library/");
            htmlTrimmed = htmlTrimmed.replace("../../", "http://127.0.0.1:" + HELP_SERVER_PORT + "/library/");

            // fix package relative 00Index.html
            String stringifiedURL = localHelpURL.toString();
            int helpIndex = stringifiedURL.indexOf("/help/");
            if (helpIndex > 0) {
                String parentPath = stringifiedURL.substring(0, helpIndex);
                htmlTrimmed = htmlTrimmed.replace("00Index.html", parentPath + "/html/00Index.html");

            }

            if (stringifiedURL.endsWith("00Index.html")) {
                String parentPath = stringifiedURL.substring(0, stringifiedURL.indexOf("/html/"));

                htmlTrimmed = htmlTrimmed.replace("href=\"", "href=\"" + parentPath + "/html/");
                // todo  DESCRIPTION, NEWS and code demos links (are correct but not feteched) and alphabetical listing links are broken
            }


            // Replace links with internal ones that are correctly handled by
            // com.intellij.codeInsight.documentation.DocumentationManager.navigateByLink()
            // See https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000095710-Intercept-clicks-in-documentation-popup-
//            htmlTrimmed = transformLinks(htmlTrimmed);
            htmlTrimmed = htmlTrimmed.replace("http://127.0.0.1:" + HELP_SERVER_PORT + "/", "psi_element://");
//            http://127.0.0.1:25593/library/base/html/file.info.html
            return htmlTrimmed;
        } catch (IOException e) {
            // server timed out??
//            if(e.getMessage().contains("response code: 500 "))
            e.printStackTrace();
        }
        return null;
    }


    private static URL restoreInterceptedLink(PsiElement reference) {
        PsiElementPattern.Capture<RCallExpression> localLinkPattern
                = psiElement(RCallExpression.class).withChild(psiElement(RReferenceExpression.class).withText("help_url"));

        if (!localLinkPattern.accepts(reference)) {
            return null;
        }

        String linkPath = ((RCallExpression) reference).getArgumentList().getExpressionList().get(0).getText();
        linkPath = CharMatcher.anyOf("\"").trimFrom(linkPath);

        return makeURL("http://127.0.0.1:" + HELP_SERVER_PORT + "/" + linkPath);
    }


    @Nullable
    private static String detectPackage(PsiElement reference) {
        // ease R documentation lookup by detecting package if possible
        String packageName = null;

        // if possible resolve package from namespace prefix
        if (reference instanceof RReferenceExpressionImpl &&
                ((RReferenceExpressionImpl) reference).getNamespace() != null) {
            packageName = ((RReferenceExpressionImpl) reference).getNamespace();

        }


        // also make sure that we can provide help in skeleton files
        if (packageName == null && isLibraryElement(reference)) {
            packageName = RResolver.getTrimmedFileName(reference);
        }

        // make sure to pull correct help for rexported symbols
        if (isLibraryElement(reference)) {
            if (reference instanceof RAssignmentStatement) {
                RPsiElement assignedValue = ((RAssignmentStatement) reference).getAssignedValue();
                if (assignedValue instanceof RReferenceExpression) {
                    packageName = ((RReferenceExpression) assignedValue).getNamespace();
                }
            }
        }
        return packageName;
    }


    public static void startHelpServer(@Nullable Integer userDefinedPort) {
        String interpreter = RSettings.getInstance().getInterpreterPath();

        if (interpreter == null) {
            return;
        }

        String scriptText = "cat( tools::startDynamicHelp(start = TRUE)); Sys.sleep(3600)";

        if (userDefinedPort != null) {
            String custPortOption = "options(help.ports = " + userDefinedPort + ")";

            scriptText = custPortOption + "; " + scriptText;
        }


        String[] getPckgsCmd = new String[]{interpreter, "--vanilla", "--quiet", "--slave", "-e", scriptText};

        try {
            final CapturingProcessHandler processHandler = new CapturingProcessHandler(new GeneralCommandLine(getPckgsCmd));
            ProcessOutput processOutput = processHandler.runProcess(1000, false);

            if (userDefinedPort == null) {
                HELP_SERVER_PORT = Integer.parseInt(processOutput.getStdout());
            } else {
                HELP_SERVER_PORT = userDefinedPort;
            }
        } catch (Throwable e) {
            LOG.info("Failed to run start help-server");
        }
    }


    public static boolean isLibraryElement(PsiElement element) {
        return element != null &&
                Strings.nullToEmpty(
                        element.getContainingFile().getVirtualFile().getCanonicalPath()
                ).contains(SKELETON_DIR_NAME);
    }


    public static URL makeURL(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }


    public static void sleep(int timeMS) {
        try {
            Thread.sleep(timeMS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
