package com.smartbear.ready.jenkins;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Proc;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.remoting.VirtualChannel;
import org.apache.commons.lang.StringUtils;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.annotation.Nonnull;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

class ProcessRunner {
    public static final String READYAPI_REPORT_DIRECTORY = File.separator + "ReadyAPI_report";
    public static final String REPORT_FORMAT = "PDF";
    private static final String TESTRUNNER_NAME = "testrunner";
    private static final String LAST_ELEMENT_TO_READ = "con:soapui-project";
    private static final String ATTRIBUTE_TO_CHECK = "updated";
    private static final String TERMINATION_STRING = "Please enter absolute path of the license file";
    private static final String SH = ".sh";
    private static final String BAT = ".bat";
    private static final String REPORT_CREATED_DETERMINANT = "Created report at";
    private static final String SOAPUI_PRO_TESTRUNNER_DETERMINANT = "com.smartbear.ready.cmd.runner.pro.SoapUIProTestCaseRunner";
    private static final String DEFAULT_PLUGIN_VERSION = "1.0";
    private static final String SOAPUI_PRO_FUNCTIONAL_TESTING_PLUGIN_INFO = "/soapUiProFunctionalTestingPluginInfo.properties";
    private static final String TESTRUNNER_VERSION_DETERMINANT = "ready-api-ui-";
    private static final String PROJECT_REPORT = "Project Report";
    private static final String TESTSUITE_REPORT = "TestSuite Report";
    private static final String TESTCASE_REPORT = "Test Case Report";
    private static final String FOLDER_NAME_SEPARATOR = "-";
    private static final int TESTRUNNER_VERSION_FOR_ANALYTICS_FIRST_NUMBER = 2;
    private static final int TESTRUNNER_VERSION_FOR_ANALYTICS_SECOND_NUMBER = 4;
    private String PRINTABLE_REPORT_CREATED_DETERMINATION = "Created report [%s]";
    private boolean isReportCreated;
    private boolean isPrintableReportCreated;
    private boolean isSoapUIProProject = false;
    private VirtualChannel channel;
    private String printableReportPath;
    private String printableReportName;

    Proc run(final ParameterContainer params, @Nonnull final Run<?, ?> run, @Nonnull Launcher launcher, @Nonnull TaskListener listener)
            throws IOException, InterruptedException {
        final PrintStream out = listener.getLogger();
        List<String> processParameterList = new ArrayList<>();
        channel = launcher.getChannel();
        String testrunnerFilePath = buildTestRunnerPath(params.getPathToTestrunner());
        FilePath testrunnerFile = new FilePath(channel, testrunnerFilePath);
        if (StringUtils.isNotBlank(testrunnerFilePath) && testrunnerFile.exists() && testrunnerFile.length() != 0) {
            try {
                if (!isSoapUIProTestrunner(testrunnerFile)) {
                    out.println("The testrunner file is not correct. Please confirm it's the testrunner for SoapUI Pro. Exiting.");
                    return null;
                }
            } catch (IOException e) {
                e.printStackTrace(out);
                return null;
            }
            processParameterList.add(testrunnerFilePath);
        } else {
            out.println("Failed to load testrunner file [" + testrunnerFilePath + "]");
            return null;
        }

        String reportDirectoryPath = params.getWorkspace() + READYAPI_REPORT_DIRECTORY;
        setReportDirectory(reportDirectoryPath);
        processParameterList.addAll(Arrays.asList("-f", reportDirectoryPath));

        processParameterList.add("-r");
        processParameterList.add("-j");
        processParameterList.add("-J");
        processParameterList.addAll(Arrays.asList("-F", REPORT_FORMAT));
        boolean isPrintableReportTypeSet = false;
        String testSuite = params.getTestSuite();
        String testCase = params.getTestCase();
        if (StringUtils.isNotBlank(testCase)) {
            if (StringUtils.isNotBlank(testSuite)) {
                processParameterList.addAll(Arrays.asList("-c", testCase));
                processParameterList.addAll(Arrays.asList("-R", TESTCASE_REPORT));
                setPrintableReportParams(File.separator + getTestSuiteFolderName(testSuite) + File.separator +
                        getTestCaseFolderName(testCase) + File.separator, TESTCASE_REPORT);
                isPrintableReportTypeSet = true;
            } else {
                out.println("Enter a testsuite for the specified testcase. Exiting.");
                return null;
            }
        }
        if (StringUtils.isNotBlank(testSuite)) {
            processParameterList.addAll(Arrays.asList("-s", testSuite));
            if (!isPrintableReportTypeSet) {
                processParameterList.addAll(Arrays.asList("-R", TESTSUITE_REPORT));
                setPrintableReportParams(File.separator + getTestSuiteFolderName(testSuite) + File.separator,
                        TESTSUITE_REPORT);
                isPrintableReportTypeSet = true;
            }
        }
        if (StringUtils.isNotBlank(params.getProjectPassword())) {
            processParameterList.addAll(Arrays.asList("-x", params.getProjectPassword()));
        }
        if (StringUtils.isNotBlank(params.getEnvironment())) {
            processParameterList.addAll(Arrays.asList("-E", params.getEnvironment()));
        }

        String projectFilePath = params.getPathToProjectFile();
        FilePath projectFile = new FilePath(channel, projectFilePath);
        if (StringUtils.isNotBlank(projectFilePath) && projectFile.exists() && projectFile.length() != 0) {
            try {
                checkIfSoapUIProProject(projectFile);
            } catch (Exception e) {
                e.printStackTrace(out);
                return null;
            }
            if (!isSoapUIProProject) {
                out.println("The project is not a SoapUI Pro project! Exiting.");
                return null;
            }
            processParameterList.add(projectFilePath);
        } else {
            out.println("Failed to load the project file [" + projectFilePath + "]");
            return null;
        }

        if (!isPrintableReportTypeSet) {
            processParameterList.addAll(Arrays.asList("-R", PROJECT_REPORT));
            setPrintableReportParams(File.separator, PROJECT_REPORT);
            isPrintableReportTypeSet = true;
        }

        if (shouldSendAnalytics(testrunnerFile)) {
            Properties properties = new Properties();
            properties.load(ProcessRunner.class.getResourceAsStream(SOAPUI_PRO_FUNCTIONAL_TESTING_PLUGIN_INFO));
            String version = properties.getProperty("version", DEFAULT_PLUGIN_VERSION);
            processParameterList.addAll(Arrays.asList("-q", version));
        }

        isReportCreated = false;
        isPrintableReportCreated = false;
        Launcher.ProcStarter processStarter = launcher.launch().cmds(processParameterList).envs(run.getEnvironment(listener)).readStdout().quiet(true);
        out.println("Starting SoapUI Pro functional test.");

        final Proc process = processStarter.start();
        final BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getStdout()));
        new Thread(new Runnable() {
            public void run() {
                String s;
                try {
                    while ((s = bufferedReader.readLine()) != null) {
                        out.println(s);
                        if (s.contains(TERMINATION_STRING)) {
                            out.println("No license was found! Exiting.");
                            run.setResult(Result.FAILURE);
                            process.kill();
                            return;
                        }
                        if (s.contains(REPORT_CREATED_DETERMINANT)) {
                            isReportCreated = true;
                        }
                        if (s.contains(PRINTABLE_REPORT_CREATED_DETERMINATION)) {
                            isPrintableReportCreated = true;
                        }
                    }
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace(out);
                }
            }
        }).start();

        return process;
    }

    private String buildTestRunnerPath(String pathToTestrunnerFile) throws IOException, InterruptedException {
        if (!StringUtils.isNotBlank(pathToTestrunnerFile)) {
            return "";
        }
        if (!new FilePath(channel, pathToTestrunnerFile).isDirectory()) {
            return pathToTestrunnerFile;
        }
        if (System.getProperty("os.name").contains("Windows")) {
            return pathToTestrunnerFile + File.separator + TESTRUNNER_NAME + BAT;
        } else {
            return pathToTestrunnerFile + File.separator + TESTRUNNER_NAME + SH;
        }
    }

    private boolean isSoapUIProTestrunner(FilePath testrunnerFile) throws IOException, InterruptedException {
        return testrunnerFile.readToString().contains(SOAPUI_PRO_TESTRUNNER_DETERMINANT);
    }

    private void setReportDirectory(String reportDirectoryPath) throws IOException, InterruptedException {
        FilePath reportDirectoryFile = new FilePath(channel, reportDirectoryPath);
        if (!reportDirectoryFile.exists()) {
            reportDirectoryFile.mkdirs();
        }
    }

    private void checkIfSoapUIProProject(FilePath projectFile) throws Exception {
        //if project is composite, it is SoapUI Pro project also
        if (projectFile.isDirectory()) {
            isSoapUIProProject = true;
            return;
        }
        SAXParserFactory factory = SAXParserFactory.newInstance();
        SAXParser saxParser = factory.newSAXParser();
        try {
            saxParser.parse(projectFile.read(), new ReadXmlUpToSpecificElementSaxParser(LAST_ELEMENT_TO_READ));
        } catch (MySAXTerminatorException exp) {
            //nothing to do, expected
        }
    }

    private boolean shouldSendAnalytics(FilePath testrunnerFile) throws IOException, InterruptedException {
        String testrunnerFileToString = testrunnerFile.readToString();
        if (testrunnerFileToString.contains(TESTRUNNER_VERSION_DETERMINANT)) {
            int startFromIndex = testrunnerFileToString.indexOf(TESTRUNNER_VERSION_DETERMINANT) + TESTRUNNER_VERSION_DETERMINANT.length();
            int firstVersionNumber = Character.getNumericValue(testrunnerFileToString.charAt(startFromIndex));
            if (firstVersionNumber >= TESTRUNNER_VERSION_FOR_ANALYTICS_FIRST_NUMBER) {
                int secondVersionIndex = Character.getNumericValue(testrunnerFileToString.charAt(startFromIndex + 2));
                if (secondVersionIndex >= TESTRUNNER_VERSION_FOR_ANALYTICS_SECOND_NUMBER) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setPrintableReportParams(String printableReportPath, String printableReportType) {
        this.printableReportPath = printableReportPath;
        this.printableReportName = printableReportType + "." + REPORT_FORMAT.toLowerCase();
        PRINTABLE_REPORT_CREATED_DETERMINATION = String.format(PRINTABLE_REPORT_CREATED_DETERMINATION, printableReportType);
    }

    //strange folder name creation in ReadyAPI
    //TODO: make good folder name for test suite and test case
    private String getTestSuiteFolderName(String testSuite) {
        return testSuite.replaceAll("\\\\", "").replaceAll("/", "")
                .replaceAll("\\.", "").replaceAll("\\s", FOLDER_NAME_SEPARATOR);
    }

    private String getTestCaseFolderName(String testCase) {
        return testCase.replaceAll("\\\\", "").replaceAll("/", "")
                .replaceAll("\\.", "").replaceAll("\\s", FOLDER_NAME_SEPARATOR);
    }

    protected boolean isReportCreated() {
        return isReportCreated;
    }

    protected boolean isPrintableReportCreated() {
        return isPrintableReportCreated;
    }

    protected String getPrintableReportName() {
        return this.printableReportName;
    }

    protected String getPrintableReportPath() {
        return this.printableReportPath;
    }

    private class ReadXmlUpToSpecificElementSaxParser extends DefaultHandler {
        private final String lastElementToRead;

        ReadXmlUpToSpecificElementSaxParser(String lastElementToRead) {
            this.lastElementToRead = lastElementToRead;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) throws MySAXTerminatorException {
            if (lastElementToRead.equals(qName)) {
                String value = attributes.getValue(ATTRIBUTE_TO_CHECK);
                if (value != null) {
                    isSoapUIProProject = !value.isEmpty();
                }
                throw new MySAXTerminatorException();
            }
        }
    }

    private class MySAXTerminatorException extends SAXException {
    }

}
