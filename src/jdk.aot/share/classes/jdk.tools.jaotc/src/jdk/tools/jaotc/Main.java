/*
 * Copyright (c) 2016, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.tools.jaotc;

import static org.graalvm.compiler.core.common.GraalOptions.GeneratePIC;
import static org.graalvm.compiler.core.common.GraalOptions.ImmutableCode;
import static org.graalvm.compiler.hotspot.meta.HotSpotAOTProfilingPlugin.Options.TieredAOT;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.stream.Stream;

import jdk.tools.jaotc.binformat.BinaryContainer;
import jdk.tools.jaotc.binformat.ByteContainer;
import jdk.tools.jaotc.collect.*;
import jdk.tools.jaotc.collect.classname.ClassNameSourceProvider;
import jdk.tools.jaotc.collect.directory.DirectorySourceProvider;
import jdk.tools.jaotc.collect.jar.JarSourceProvider;
import jdk.tools.jaotc.collect.module.ModuleSourceProvider;
import jdk.tools.jaotc.utils.Timer;

import org.graalvm.compiler.api.replacements.SnippetReflectionProvider;
import org.graalvm.compiler.api.runtime.GraalJVMCICompiler;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.DebugContext.Activation;
import org.graalvm.compiler.hotspot.CompilerConfigurationFactory;
import org.graalvm.compiler.hotspot.GraalHotSpotVMConfig;
import org.graalvm.compiler.hotspot.HotSpotGraalCompilerFactory;
import org.graalvm.compiler.hotspot.HotSpotGraalOptionValues;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntimeProvider;
import org.graalvm.compiler.hotspot.HotSpotHostBackend;
import org.graalvm.compiler.java.GraphBuilderPhase;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.BasePhase;
import org.graalvm.compiler.phases.PhaseSuite;
import org.graalvm.compiler.phases.tiers.HighTierContext;
import org.graalvm.compiler.printer.GraalDebugHandlersFactory;
import org.graalvm.compiler.runtime.RuntimeProvider;

import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.runtime.JVMCI;

public class Main implements LogPrinter {
    static class BadArgs extends Exception {
        private static final long serialVersionUID = 1L;
        final String key;
        final Object[] args;
        boolean showUsage;

        BadArgs(String key, Object... args) {
            super(MessageFormat.format(key, args));
            this.key = key;
            this.args = args;
        }

        BadArgs showUsage(boolean b) {
            showUsage = b;
            return this;
        }
    }

    abstract static class Option {
        final String help;
        final boolean hasArg;
        final String[] aliases;

        Option(String help, boolean hasArg, String... aliases) {
            this.help = help;
            this.hasArg = hasArg;
            this.aliases = aliases;
        }

        boolean isHidden() {
            return false;
        }

        boolean matches(String opt) {
            for (String a : aliases) {
                if (a.equals(opt)) {
                    return true;
                } else if (opt.startsWith("--") && hasArg && opt.startsWith(a + "=")) {
                    return true;
                }
            }
            return false;
        }

        boolean ignoreRest() {
            return false;
        }

        abstract void process(Main task, String opt, String arg) throws BadArgs;
    }

    static Option[] recognizedOptions = {new Option("  --output <file>            Output file name", true, "--output") {
        @Override
        void process(Main task, String opt, String arg) {
            String name = arg;
            task.options.outputName = name;
        }
    }, new Option("  --class-name <class names> List of classes to compile", true, "--class-name", "--classname") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.files.addAll(ClassSearch.makeList(ClassNameSourceProvider.TYPE, arg));
        }
    }, new Option("  --jar <jarfiles>           List of jar files to compile", true, "--jar") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.files.addAll(ClassSearch.makeList(JarSourceProvider.TYPE, arg));
        }
    }, new Option("  --module <modules>         List of modules to compile", true, "--module") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.files.addAll(ClassSearch.makeList(ModuleSourceProvider.TYPE, arg));
        }
    }, new Option("  --directory <dirs>         List of directories where to search for files to compile", true, "--directory") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.files.addAll(ClassSearch.makeList(DirectorySourceProvider.TYPE, arg));
        }
    }, new Option("  --search-path <dirs>       List of directories where to search for specified files", true, "--search-path") {
        @Override
        void process(Main task, String opt, String arg) {
            String[] elements = arg.split(":");
            task.options.searchPath.add(elements);
        }
    }, new Option("  --compile-commands <file>  Name of file with compile commands", true, "--compile-commands") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.methodList = arg;
        }
    }, new Option("  --compile-for-tiered       Generate profiling code for tiered compilation", false, "--compile-for-tiered") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.tiered = true;
        }
    }, new Option("  --compile-with-assertions  Compile with java assertions", false, "--compile-with-assertions") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.compileWithAssertions = true;
        }
    }, new Option("  --compile-threads <number> Number of compilation threads to be used", true, "--compile-threads", "--threads") {
        @Override
        void process(Main task, String opt, String arg) {
            int threads = Integer.parseInt(arg);
            final int available = Runtime.getRuntime().availableProcessors();
            if (threads <= 0) {
                task.warning("invalid number of threads specified: {0}, using: {1}", threads, available);
                threads = available;
            }
            if (threads > available) {
                task.warning("too many threads specified: {0}, limiting to: {1}", threads, available);
            }
            task.options.threads = Integer.min(threads, available);
        }
    }, new Option("  --ignore-errors            Ignores all exceptions thrown during class loading", false, "--ignore-errors") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.ignoreClassLoadingErrors = true;
        }
    }, new Option("  --exit-on-error            Exit on compilation errors", false, "--exit-on-error") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.exitOnError = true;
        }
    }, new Option("  --info                     Print information during compilation", false, "--info") {
        @Override
        void process(Main task, String opt, String arg) throws BadArgs {
            task.options.info = true;
        }
    }, new Option("  --verbose                  Print verbose information", false, "--verbose") {
        @Override
        void process(Main task, String opt, String arg) throws BadArgs {
            task.options.info = true;
            task.options.verbose = true;
        }
    }, new Option("  --debug                    Print debug information", false, "--debug") {
        @Override
        void process(Main task, String opt, String arg) throws BadArgs {
            task.options.info = true;
            task.options.verbose = true;
            task.options.debug = true;
        }
    }, new Option("  --help                     Print this usage message", false, "--help") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.help = true;
        }
    }, new Option("  --version                  Version information", false, "--version") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.version = true;
        }
    }, new Option("  --linker-path              Full path to linker executable", true, "--linker-path") {
        @Override
        void process(Main task, String opt, String arg) {
            task.options.linkerpath = arg;
        }
    }, new Option("  -J<flag>                   Pass <flag> directly to the runtime system", false, "-J") {
        @Override
        void process(Main task, String opt, String arg) {
        }
    }};

    public static class Options {
        public List<SearchFor> files = new LinkedList<>();
        public String outputName = defaultOutputName();
        public String methodList;
        public List<ClassSource> sources = new ArrayList<>();
        public String linkerpath = null;
        public SearchPath searchPath = new SearchPath();

        /**
         * We don't see scaling beyond 16 threads.
         */
        private static final int COMPILER_THREADS = 16;

        public int threads = Integer.min(COMPILER_THREADS, Runtime.getRuntime().availableProcessors());

        public boolean ignoreClassLoadingErrors;
        public boolean exitOnError;
        public boolean info;
        public boolean verbose;
        public boolean debug;
        public boolean help;
        public boolean version;
        public boolean compileWithAssertions;
        public boolean tiered;

        private static String defaultOutputName() {
            String osName = System.getProperty("os.name");
            String name = "unnamed.";
            String ext;

            switch (osName) {
                case "Linux":
                case "SunOS":
                    ext = "so";
                    break;
                case "Mac OS X":
                    ext = "dylib";
                    break;
                default:
                    if (osName.startsWith("Windows")) {
                        ext = "dll";
                    } else {
                        ext = "so";
                    }
            }

            return name + ext;
        }
    }

    /* package */final Options options = new Options();

    /**
     * Logfile.
     */
    private static FileWriter logFile = null;

    private static final int EXIT_OK = 0;        // No errors.
    private static final int EXIT_CMDERR = 2;    // Bad command-line arguments and/or switches.
    private static final int EXIT_ABNORMAL = 4;  // Terminated abnormally.

    private static final String PROGNAME = "jaotc";

    private static final String JVM_VERSION = System.getProperty("java.runtime.version");

    public static void main(String[] args) throws Exception {
        Main t = new Main();
        final int exitCode = t.run(args);
        System.exit(exitCode);
    }

    private int run(String[] args) {
        if (log == null) {
            log = new PrintWriter(System.out);
        }

        try {
            handleOptions(args);
            if (options.help) {
                showHelp();
                return EXIT_OK;
            }
            if (options.version) {
                showVersion();
                return EXIT_OK;
            }

            printlnInfo("Compiling " + options.outputName + "...");
            final long start = System.currentTimeMillis();
            if (!run()) {
                return EXIT_ABNORMAL;
            }
            final long end = System.currentTimeMillis();
            printlnInfo("Total time: " + (end - start) + " ms");

            return EXIT_OK;
        } catch (BadArgs e) {
            reportError(e.key, e.args);
            if (e.showUsage) {
                showUsage();
            }
            return EXIT_CMDERR;
        } catch (Exception e) {
            e.printStackTrace();
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private static String humanReadableByteCount(long bytes) {
        int unit = 1024;

        if (bytes < unit) {
            return bytes + " B";
        }

        int exp = (int) (Math.log(bytes) / Math.log(unit));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %cB", bytes / Math.pow(unit, exp), pre);
    }

    void printMemoryUsage() {
        if (options.verbose) {
            MemoryUsage memusage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            float freeratio = 1f - (float) memusage.getUsed() / memusage.getCommitted();
            log.format(" [used: %-7s, comm: %-7s, freeRatio ~= %.1f%%]",
                            humanReadableByteCount(memusage.getUsed()),
                            humanReadableByteCount(memusage.getCommitted()),
                            freeratio * 100);
        }
    }

    /**
     * Visual Studio supported versions Search Order is: VS2013, VS2015, VS2012
     */
    public enum VSVERSIONS {
        VS2013("VS120COMNTOOLS", "C:\\Program Files (x86)\\Microsoft Visual Studio 12.0\\VC\\bin\\amd64\\link.exe"),
        VS2015("VS140COMNTOOLS", "C:\\Program Files (x86)\\Microsoft Visual Studio 14.0\\VC\\bin\\amd64\\link.exe"),
        VS2012("VS110COMNTOOLS", "C:\\Program Files (x86)\\Microsoft Visual Studio 11.0\\VC\\bin\\amd64\\link.exe");

        private final String envvariable;
        private final String wkp;

        VSVERSIONS(String envvariable, String wellknownpath) {
            this.envvariable = envvariable;
            this.wkp = wellknownpath;
        }

        String EnvVariable() {
            return envvariable;
        }

        String WellKnownPath() {
            return wkp;
        }
    }

    /**
     * Search for Visual Studio link.exe Search Order is: VS2013, VS2015, VS2012
     */
    private static String getWindowsLinkPath() {
        String link = "\\VC\\bin\\amd64\\link.exe";

        /**
         * First try searching the paths pointed to by the VS environment variables.
         */
        for (VSVERSIONS vs : VSVERSIONS.values()) {
            String vspath = System.getenv(vs.EnvVariable());
            if (vspath != null) {
                File commonTools = new File(vspath);
                File vsRoot = commonTools.getParentFile().getParentFile();
                File linkPath = new File(vsRoot, link);
                if (linkPath.exists())
                    return linkPath.getPath();
            }
        }

        /**
         * If we didn't find via the VS environment variables, try the well known paths
         */
        for (VSVERSIONS vs : VSVERSIONS.values()) {
            String wkp = vs.WellKnownPath();
            if (new File(wkp).exists()) {
                return wkp;
            }
        }

        return null;
    }

    @SuppressWarnings("try")
    private boolean run() throws Exception {
        openLog();

        try {
            CompilationSpec compilationRestrictions = collectSpecifiedMethods();

            Set<Class<?>> classesToCompile = new HashSet<>();

            try (Timer t = new Timer(this, "")) {
                FileSupport fileSupport = new FileSupport();
                ClassSearch lookup = new ClassSearch();
                lookup.addProvider(new ModuleSourceProvider());
                lookup.addProvider(new ClassNameSourceProvider(fileSupport));
                lookup.addProvider(new JarSourceProvider());
                lookup.addProvider(new DirectorySourceProvider(fileSupport));

                List<LoadedClass> found = null;
                try {
                    found = lookup.search(options.files, options.searchPath);
                } catch (InternalError e) {
                    reportError(e);
                    return false;
                }

                for (LoadedClass loadedClass : found) {
                    classesToCompile.add(loadedClass.getLoadedClass());
                }

                printInfo(classesToCompile.size() + " classes found");
            }

            OptionValues graalOptions = HotSpotGraalOptionValues.HOTSPOT_OPTIONS;
            // Setting -Dgraal.TieredAOT overrides --compile-for-tiered
            if (!TieredAOT.hasBeenSet(graalOptions)) {
                graalOptions = new OptionValues(graalOptions, TieredAOT, options.tiered);
            }
            graalOptions = new OptionValues(graalOptions, GeneratePIC, true, ImmutableCode, true);
            GraalJVMCICompiler graalCompiler = HotSpotGraalCompilerFactory.createCompiler(JVMCI.getRuntime(), graalOptions, CompilerConfigurationFactory.selectFactory(null, graalOptions));
            HotSpotGraalRuntimeProvider runtime = (HotSpotGraalRuntimeProvider) graalCompiler.getGraalRuntime();
            HotSpotHostBackend backend = (HotSpotHostBackend) runtime.getCapability(RuntimeProvider.class).getHostBackend();
            MetaAccessProvider metaAccess = backend.getProviders().getMetaAccess();
            GraalFilters filters = new GraalFilters(metaAccess);

            List<AOTCompiledClass> classes;

            try (Timer t = new Timer(this, "")) {
                classes = collectMethodsToCompile(classesToCompile, compilationRestrictions, filters, metaAccess);
            }

            // Free memory!
            try (Timer t = options.verbose ? new Timer(this, "Freeing memory") : null) {
                printMemoryUsage();
                compilationRestrictions = null;
                classesToCompile = null;
                System.gc();
            }

            AOTBackend aotBackend = new AOTBackend(this, graalOptions, backend, filters);
            SnippetReflectionProvider snippetReflection = aotBackend.getProviders().getSnippetReflection();
            AOTCompiler compiler = new AOTCompiler(this, graalOptions, aotBackend, options.threads);
            classes = compiler.compileClasses(classes);

            GraalHotSpotVMConfig graalHotSpotVMConfig = runtime.getVMConfig();
            PhaseSuite<HighTierContext> graphBuilderSuite = aotBackend.getGraphBuilderSuite();
            ListIterator<BasePhase<? super HighTierContext>> iterator = graphBuilderSuite.findPhase(GraphBuilderPhase.class);
            GraphBuilderConfiguration graphBuilderConfig = ((GraphBuilderPhase) iterator.previous()).getGraphBuilderConfig();

            // Free memory!
            try (Timer t = options.verbose ? new Timer(this, "Freeing memory") : null) {
                printMemoryUsage();
                aotBackend = null;
                compiler = null;
                System.gc();
            }

            BinaryContainer binaryContainer = new BinaryContainer(graalOptions, graalHotSpotVMConfig, graphBuilderConfig, JVM_VERSION);
            DataBuilder dataBuilder = new DataBuilder(this, backend, classes, binaryContainer);

            try (DebugContext debug = DebugContext.create(graalOptions, new GraalDebugHandlersFactory(snippetReflection)); Activation a = debug.activate()) {
                dataBuilder.prepareData(debug);
            }

            // Print information about section sizes
            printContainerInfo(binaryContainer.getHeaderContainer().getContainer());
            printContainerInfo(binaryContainer.getConfigContainer());
            printContainerInfo(binaryContainer.getKlassesOffsetsContainer());
            printContainerInfo(binaryContainer.getMethodsOffsetsContainer());
            printContainerInfo(binaryContainer.getKlassesDependenciesContainer());
            printContainerInfo(binaryContainer.getStubsOffsetsContainer());
            printContainerInfo(binaryContainer.getMethodMetadataContainer());
            printContainerInfo(binaryContainer.getCodeContainer());
            printContainerInfo(binaryContainer.getCodeSegmentsContainer());
            printContainerInfo(binaryContainer.getConstantDataContainer());
            printContainerInfo(binaryContainer.getMetaspaceGotContainer());
            printContainerInfo(binaryContainer.getMetadataGotContainer());
            printContainerInfo(binaryContainer.getMethodStateContainer());
            printContainerInfo(binaryContainer.getOopGotContainer());
            printContainerInfo(binaryContainer.getMetaspaceNamesContainer());

            // Free memory!
            try (Timer t = options.verbose ? new Timer(this, "Freeing memory") : null) {
                printMemoryUsage();
                backend = null;
                for (AOTCompiledClass aotCompClass : classes) {
                    aotCompClass.clear();
                }
                classes.clear();
                classes = null;
                dataBuilder = null;
                binaryContainer.freeMemory();
                System.gc();
            }

            String name = options.outputName;
            String objectFileName = name;

            String libraryFileName = name;

            String linkerCmd;
            String linkerPath;
            String osName = System.getProperty("os.name");

            switch (osName) {
                case "Linux":
                    if (name.endsWith(".so")) {
                        objectFileName = name.substring(0, name.length() - ".so".length());
                    }
                    linkerPath = (options.linkerpath != null) ? options.linkerpath : "ld";
                    linkerCmd = linkerPath + " -shared -z noexecstack -o " + libraryFileName + " " + objectFileName;
                    break;
                case "SunOS":
                    if (name.endsWith(".so")) {
                        objectFileName = name.substring(0, name.length() - ".so".length());
                    }
                    objectFileName = objectFileName + ".o";
                    linkerPath = (options.linkerpath != null) ? options.linkerpath : "ld";
                    linkerCmd = linkerPath + " -shared -o " + libraryFileName + " " + objectFileName;
                    break;
                case "Mac OS X":
                    if (name.endsWith(".dylib")) {
                        objectFileName = name.substring(0, name.length() - ".dylib".length());
                    }
                    objectFileName = objectFileName + ".o";
                    linkerPath = (options.linkerpath != null) ? options.linkerpath : "ld";
                    linkerCmd = linkerPath + " -dylib -o " + libraryFileName + " " + objectFileName;
                    break;
                default:
                    if (osName.startsWith("Windows")) {
                        if (name.endsWith(".dll")) {
                            objectFileName = name.substring(0, name.length() - ".dll".length());
                        }
                        objectFileName = objectFileName + ".obj";
                        linkerPath = (options.linkerpath != null) ? options.linkerpath : getWindowsLinkPath();
                        if (linkerPath == null) {
                            throw new InternalError("Can't locate Microsoft Visual Studio amd64 link.exe");
                        }
                        linkerCmd = linkerPath + " /DLL /OPT:NOREF /NOLOGO /NOENTRY" + " /OUT:" + libraryFileName + " " + objectFileName;
                        break;
                    } else {
                        throw new InternalError("Unsupported platform: " + osName);
                    }
            }

            try (Timer t = new Timer(this, "Creating binary: " + objectFileName)) {
                binaryContainer.createBinary(objectFileName, JVM_VERSION);
            }

            // Free memory!
            try (Timer t = options.verbose ? new Timer(this, "Freeing memory") : null) {
                printMemoryUsage();
                binaryContainer = null;
                System.gc();
            }

            try (Timer t = new Timer(this, "Creating shared library: " + libraryFileName)) {
                Process p = Runtime.getRuntime().exec(linkerCmd);
                final int exitCode = p.waitFor();
                if (exitCode != 0) {
                    InputStream stderr = p.getErrorStream();
                    BufferedReader br = new BufferedReader(new InputStreamReader(stderr));
                    Stream<String> lines = br.lines();
                    StringBuilder sb = new StringBuilder();
                    lines.iterator().forEachRemaining(e -> sb.append(e));
                    throw new InternalError(sb.toString());
                }
                File objFile = new File(objectFileName);
                if (objFile.exists()) {
                    if (!objFile.delete()) {
                        throw new InternalError("Failed to delete " + objectFileName + " file");
                    }
                }
                // Make non-executable for all.
                File libFile = new File(libraryFileName);
                if (libFile.exists() && !osName.startsWith("Windows")) {
                    if (!libFile.setExecutable(false, false)) {
                        throw new InternalError("Failed to change attribute for " + libraryFileName + " file");
                    }
                }
            }

            printVerbose("Final memory  ");
            printMemoryUsage();
            printlnVerbose("");

        } finally {
            closeLog();
        }
        return true;
    }

    private void addMethods(AOTCompiledClass aotClass, ResolvedJavaMethod[] methods, CompilationSpec compilationRestrictions, GraalFilters filters) {
        for (ResolvedJavaMethod m : methods) {
            addMethod(aotClass, m, compilationRestrictions, filters);
        }
    }

    private void addMethod(AOTCompiledClass aotClass, ResolvedJavaMethod method, CompilationSpec compilationRestrictions, GraalFilters filters) {
        // Don't compile native or abstract methods.
        if (!method.hasBytecodes()) {
            return;
        }
        if (!compilationRestrictions.shouldCompileMethod(method)) {
            return;
        }
        if (!filters.shouldCompileMethod(method)) {
            return;
        }

        aotClass.addMethod(method);
        printlnVerbose("  added " + method.getName() + method.getSignature().toMethodDescriptor());
    }

    private void printContainerInfo(ByteContainer container) {
        printlnVerbose(container.getContainerName() + ": " + container.getByteStreamSize() + " bytes");
    }

    PrintWriter log;

    private void handleOptions(String[] args) throws BadArgs {
        if (args.length == 0) {
            options.help = true;
            return;
        }

        // Make checkstyle happy.
        int i = 0;
        for (; i < args.length; i++) {
            String arg = args[i];

            if (arg.charAt(0) == '-') {
                Option option = getOption(arg);
                String param = null;

                if (option.hasArg) {
                    if (arg.startsWith("--") && arg.indexOf('=') > 0) {
                        param = arg.substring(arg.indexOf('=') + 1, arg.length());
                    } else if (i + 1 < args.length) {
                        param = args[++i];
                    }

                    if (param == null || param.isEmpty() || param.charAt(0) == '-') {
                        throw new BadArgs("missing argument for option: {0}", arg).showUsage(true);
                    }
                }

                option.process(this, arg, param);

                if (option.ignoreRest()) {
                    break;
                }
            } else {
                options.files.add(new SearchFor(arg));
            }
        }
    }

    private static Option getOption(String name) throws BadArgs {
        for (Option o : recognizedOptions) {
            if (o.matches(name)) {
                return o;
            }
        }
        throw new BadArgs("unknown option: {0}", name).showUsage(true);
    }

    public void printInfo(String message) {
        if (options.info) {
            log.print(message);
            log.flush();
        }
    }

    public void printlnInfo(String message) {
        if (options.info) {
            log.println(message);
            log.flush();
        }
    }

    public void printVerbose(String message) {
        if (options.verbose) {
            log.print(message);
            log.flush();
        }
    }

    public void printlnVerbose(String message) {
        if (options.verbose) {
            log.println(message);
            log.flush();
        }
    }

    public void printDebug(String message) {
        if (options.debug) {
            log.print(message);
            log.flush();
        }
    }

    public void printlnDebug(String message) {
        if (options.debug) {
            log.println(message);
            log.flush();
        }
    }

    public void printError(String message) {
        log.println("Error: " + message);
        log.flush();
    }

    private void reportError(Throwable e) {
        log.println("Error: " + e.getMessage());
        if (options.info) {
            e.printStackTrace(log);
        }
        log.flush();
    }

    private void reportError(String key, Object... args) {
        printError(MessageFormat.format(key, args));
    }

    private void warning(String key, Object... args) {
        log.println("Warning: " + MessageFormat.format(key, args));
        log.flush();
    }

    private void showUsage() {
        log.println("Usage: " + PROGNAME + " <options> list");
        log.println("use --help for a list of possible options");
    }

    private void showHelp() {
        log.println("Usage: " + PROGNAME + " <options> list");
        log.println();
        log.println("  list       A : separated list of class names, modules, jar files");
        log.println("             or directories which contain class files.");
        log.println();
        log.println("where options include:");
        for (Option o : recognizedOptions) {
            String name = o.aliases[0].substring(1); // there must always be at least one name
            name = name.charAt(0) == '-' ? name.substring(1) : name;
            if (o.isHidden() || name.equals("h")) {
                continue;
            }
            log.println(o.help);
        }
    }

    private void showVersion() {
        log.println(PROGNAME + " " + JVM_VERSION);
    }

    /**
     * Collect all method we should compile.
     *
     * @return array list of AOT classes which have compiled methods.
     */
    private List<AOTCompiledClass> collectMethodsToCompile(Set<Class<?>> classesToCompile, CompilationSpec compilationRestrictions, GraalFilters filters, MetaAccessProvider metaAccess) {
        int total = 0;
        int count = 0;
        List<AOTCompiledClass> classes = new ArrayList<>();

        for (Class<?> c : classesToCompile) {
            ResolvedJavaType resolvedJavaType = metaAccess.lookupJavaType(c);
            if (filters.shouldCompileAnyMethodInClass(resolvedJavaType)) {
                AOTCompiledClass aotClass = new AOTCompiledClass(resolvedJavaType);
                printlnVerbose(" Scanning " + c.getName());

                // Constructors
                try {
                    ResolvedJavaMethod[] ctors = resolvedJavaType.getDeclaredConstructors();
                    addMethods(aotClass, ctors, compilationRestrictions, filters);
                    total += ctors.length;
                } catch (Throwable e) {
                    // If we are running in JCK mode we ignore all exceptions.
                    if (options.ignoreClassLoadingErrors) {
                        printError(c.getName() + ": " + e);
                    } else {
                        throw new InternalError(e);
                    }
                }

                // Methods
                try {
                    ResolvedJavaMethod[] methods = resolvedJavaType.getDeclaredMethods();
                    addMethods(aotClass, methods, compilationRestrictions, filters);
                    total += methods.length;
                } catch (Throwable e) {
                    // If we are running in JCK mode we ignore all exceptions.
                    if (options.ignoreClassLoadingErrors) {
                        printError(c.getName() + ": " + e);
                    } else {
                        throw new InternalError(e);
                    }
                }

                // Class initializer
                try {
                    ResolvedJavaMethod clinit = resolvedJavaType.getClassInitializer();
                    if (clinit != null) {
                        addMethod(aotClass, clinit, compilationRestrictions, filters);
                        total++;
                    }
                } catch (Throwable e) {
                    // If we are running in JCK mode we ignore all exceptions.
                    if (options.ignoreClassLoadingErrors) {
                        printError(c.getName() + ": " + e);
                    } else {
                        throw new InternalError(e);
                    }
                }

                // Found any methods to compile? Add the class.
                if (aotClass.hasMethods()) {
                    classes.add(aotClass);
                    count += aotClass.getMethodCount();
                }
            }
        }
        printInfo(total + " methods total, " + count + " methods to compile");
        return classes;
    }

    /**
     * If a file with compilation limitations is specified using the java property
     * jdk.tools.jaotc.compile.method.list, read the file's contents and collect the restrictions.
     */
    private CompilationSpec collectSpecifiedMethods() {
        CompilationSpec compilationRestrictions = new CompilationSpec();
        String methodListFileName = options.methodList;

        if (methodListFileName != null && !methodListFileName.equals("")) {
            try {
                FileReader methListFile = new FileReader(methodListFileName);
                BufferedReader readBuf = new BufferedReader(methListFile);
                String line = null;
                while ((line = readBuf.readLine()) != null) {
                    String trimmedLine = line.trim();
                    if (!trimmedLine.startsWith("#")) {
                        String[] components = trimmedLine.split(" ");
                        if (components.length == 2) {
                            String directive = components[0];
                            String pattern = components[1];
                            switch (directive) {
                                case "compileOnly":
                                    compilationRestrictions.addCompileOnlyPattern(pattern);
                                    break;
                                case "exclude":
                                    compilationRestrictions.addExcludePattern(pattern);
                                    break;
                                default:
                                    System.out.println("Unrecognized command " + directive + ". Ignoring\n\t" + line + "\n encountered in " + methodListFileName);
                            }
                        } else {
                            if (!trimmedLine.equals("")) {
                                System.out.println("Ignoring malformed line:\n\t " + line + "\n");
                            }
                        }
                    }
                }
                readBuf.close();
            } catch (FileNotFoundException e) {
                throw new InternalError("Unable to open method list file: " + methodListFileName, e);
            } catch (IOException e) {
                throw new InternalError("Unable to read method list file: " + methodListFileName, e);
            }
        }

        return compilationRestrictions;
    }

    private static void openLog() {
        int v = Integer.getInteger("jdk.tools.jaotc.logCompilation", 0);
        if (v == 0) {
            logFile = null;
            return;
        }
        // Create log file in current directory
        String fileName = "aot_compilation" + new Date().getTime() + ".log";
        Path logFilePath = Paths.get("./", fileName);
        String logFileName = logFilePath.toString();
        try {
            // Create file to which we do not append
            logFile = new FileWriter(logFileName, false);
        } catch (IOException e) {
            System.out.println("Unable to open logfile :" + logFileName + "\nNo logs will be created");
            logFile = null;
        }
    }

    public static void writeLog(String str) {
        if (logFile != null) {
            try {
                logFile.write(str + "\n");
                logFile.flush();
            } catch (IOException e) {
                // Print to console
                System.out.println(str + "\n");
            }
        }
    }

    public static void closeLog() {
        if (logFile != null) {
            try {
                logFile.close();
            } catch (IOException e) {
                // Do nothing
            }
        }
    }
}
