package com.alkimiapps.gradle.plugin.dplink.internal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.BaseStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.alkimiapps.javatools.FileUtils;

import static com.alkimiapps.javatools.Sugar.fatalGuard;
import static com.alkimiapps.javatools.Sugar.ifThen;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;

/**
 * Executes the commands that create a jre customised to contain only those Java modules needed for some specific
 * application.
 * <p>
 * The application is defined in terms of one or more jars within libs subdirectory of a build folder (i.e. standard
 * gradle location for storing jars required at runtime). DplinkExecutor iterates over the jars the libs subdirectory
 * and finds the java dependencies for each jar. Then using that list of dependencies, jlink is executed to create the
 * custom jre in the outputDir location (e.g. build/app).
 * <p>
 * Optionally the name of an executable jar and the name of a main class for an executable jar can be specified. Doing
 * so informs DplinkExecutor to create an executable script for running the app. This script is by default named "app" and is
 * created in the ${outputDir}/bin directory. If there is no executableJar but there is a mainClassName then DplinkExecutor will
 * expect that there is only a single jar in the libs subdirectory and that that jar is an executable jar. If an
 * executableJar is specified and there are multiple jars in the libs subdirectory then these jars are included in the
 * classpath of the executable.
 */
public class DplinkExecutor {

    private boolean isVerbose;
    private Path javaHome;
    private Path modulesHome;
    private boolean allJavaModules;

    public void dplink(@Nonnull DplinkConfig dplinkConfig) {

        this.isVerbose = dplinkConfig.isVerbose();
        this.javaHome = dplinkConfig.getJavaHome();
        this.modulesHome = dplinkConfig.getModulesHome();
        this.allJavaModules = dplinkConfig.isAllJavaModules();

        Optional<Stream<Path>> fileListStream = Optional.empty();

        try {

            Files.createDirectories(dplinkConfig.getBuildLibsDir());

            fatalGuard(exists(dplinkConfig.getBuildLibsDir()), "No libs dir at: " + dplinkConfig.getBuildLibsDir().getParent().toString());
            fatalGuard(isDirectory(dplinkConfig.getBuildLibsDir()), "libs is not a directory: " + dplinkConfig.getBuildLibsDir().getParent().toString());

            Set<String> dependentJavaModules = new HashSet<>();

            if(allJavaModules) {
                allJavaModules().forEach(dependentJavaModules::add);
            }else{
            if( dplinkConfig.isFatJar() && dplinkConfig.getExecutableJar().isPresent() ) {
                fileListStream = Optional.of( Stream.of( Paths.get(dplinkConfig.getExecutableJar().get()) ) );
            } else                {
                fileListStream = Optional.of( Files.list( dplinkConfig.getBuildLibsDir() ).parallel() );
            }

            fileListStream.get()
                    .flatMap(this::dependentJavaModulesOfJar)
                    .forEach(dependentJavaModules::add);
            }


            if (dependentJavaModules.size() > 0) {
                this.jlink(dependentJavaModules, dplinkConfig.getOutputDir());
                if (dplinkConfig.getMainClassName().isPresent()) {
                    this.createApp(dplinkConfig);
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            fileListStream.ifPresent(BaseStream::close);
        }
    }

    private Stream<String> allJavaModules() {
        String[] javaCommand = {this.javaHome.resolve("bin/java").toString(), "--list-modules", "--module-path", this.modulesHome.resolve( "jmods" ).toString()};

        Function<InputStream, Stream<String>> commandOutputProcessing = (InputStream jdepsInputStream) -> {
            BufferedReader jdepsReader = new BufferedReader(new InputStreamReader(jdepsInputStream));
            return jdepsReader.lines()
                    .map(String::trim)
                      .map(s -> s.replaceFirst( "@.*$", "" ))
                    .collect(Collectors.toSet())
                    .parallelStream();
        };

        return this.execCommand(javaCommand, commandOutputProcessing).orElse(Stream.of());

    }

    private Stream<String> dependentJavaModulesOfJar(@Nonnull Path jarPath) {
        String[] jdepsCommand = {this.javaHome.resolve("bin/jdeps").toString(), "--list-deps", jarPath.toString()};

        Function<InputStream, Stream<String>> commandOutputProcessing = (InputStream jdepsInputStream) -> {
            BufferedReader jdepsReader = new BufferedReader(new InputStreamReader(jdepsInputStream));
            return jdepsReader.lines()
                    .filter(s -> s.matches("^\\s*(java|jdk|javafx|oracle)\\..*$"))
                    .map(String::trim)
                    .map(s -> s.replaceFirst( "/.*$", "" ))
                    .collect(Collectors.toSet())
                    .parallelStream();
        };

        return this.execCommand(jdepsCommand, commandOutputProcessing).orElse(Stream.of());

    }

    private void jlink(@Nonnull Set<String> dependentJavaModules, @Nonnull Path outputDir) {
        String dependentJavaModulesString = dependentJavaModules.stream()
                .collect(Collectors.joining(","));

        try {
            if (Files.exists(outputDir)) {
                FileUtils.forceDelete(outputDir.toFile());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        String[] jlinkCommand = {
                this.javaHome.resolve("bin/jlink").toString(),
                "--module-path",
                this.modulesHome.toString() + "/jmods:mlib",
                "--add-modules",
                dependentJavaModulesString,
                "--output",
                outputDir.toString(),
                "--no-header-files",
                "--no-man-pages",
                "--compress=2"

        };

        this.execCommand(jlinkCommand);
    }

    private void createApp(@Nonnull DplinkConfig dplinkConfig) {

        Path jreLibPath = dplinkConfig.getOutputDir().resolve("lib");

        fatalGuard(exists(jreLibPath), "No lib dir at: " + jreLibPath.getParent().toString());
        fatalGuard(isDirectory(jreLibPath), "lib is not a directory: " + jreLibPath.getParent().toString());
        fatalGuard(dplinkConfig.getMainClassName().isPresent(), "Missing main class name - needed for executable jar");

        try {
            String executableJarName = this.executableJarName(dplinkConfig.getBuildLibsDir(), dplinkConfig.getExecutableJar());
            String classpath = this.classpath(dplinkConfig.getBuildLibsDir(), jreLibPath, executableJarName);
            FileUtils.copyDirectory(dplinkConfig.getBuildLibsDir().toFile(), jreLibPath.toFile());

            String jvmArgs = dplinkConfig.getJvmArgs().orElse("");
            String appArgs = dplinkConfig.getAppArgs().orElse("");

            this.makeAppScript(dplinkConfig.getMainClassName().get(), executableJarName, classpath, jvmArgs, appArgs, dplinkConfig.getOutputDir());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void makeAppScript(@Nonnull String mainClass, @Nonnull String executableJarName, @Nonnull String classpath,
                               @Nonnull String jvmArgs, @Nonnull String appArgs, @Nonnull Path outputDir) throws IOException {


        String commandString = outputDir.resolve("bin") + "/java " + jvmArgs + " -jar " +
                outputDir.resolve("lib") + "/" +
                executableJarName + " " + mainClass + " " + appArgs;

        if (classpath.length() > 0) {
            commandString = commandString + " -cp " + classpath;
        }

        Path appFilePath = Files.createFile(outputDir.resolve("bin/app"));
        try (BufferedWriter writer = Files.newBufferedWriter(appFilePath)) {
            writer.write("#!/usr/bin/env bash\n");
            // $* adds command line args
            writer.write(commandString + " $*\n");
        }

        this.execCommand(new String[]{"chmod", "uog+x", appFilePath.toString()});
    }

    private String classpath(@Nonnull Path buildLibsDir, @Nonnull Path jreLibDir, @Nonnull String executableJarName) throws IOException {
        return Files.list(buildLibsDir)
                // only get jars from the build libs directory that are not the executable jar
                .filter(path -> !(executableJarName.equals(path.getFileName().toString())))
                // map out just the file name
                .map(path -> path.getFileName().toString())
                // prepend the file name with the jreLibDir - because that's where the jars are at runtime
                .map(fileName -> jreLibDir.toString() + "/" + fileName)
                // collect them all together joined by a : for the path separator
                .collect(Collectors.joining(":"));
    }

    private String executableJarName(@Nonnull Path jreLibDir, Optional<String> executableJar) {
        Function<Path, String> findExecutableJar = (Path libDir) -> {
            try {
                List<Path> jarFiles = Files.list(libDir).collect(Collectors.toList());
                fatalGuard(jarFiles.size() == 1, "Expected only a single jar in " +
                        jreLibDir.toString() + " but found " + jarFiles.size() + ". Try using the executableJar property to specify the executable jar file name.");
                return jarFiles.get(0).getFileName().toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        String executableJarName = executableJar.orElseGet(() -> findExecutableJar.apply(jreLibDir));

        fatalGuard(exists(jreLibDir.resolve(executableJarName)), "Executable jar " +
                jreLibDir.resolve(executableJarName).toString() + " does not exist.");

        return executableJarName;
    }

    private void execCommand(@Nonnull String[] command) {
        this.execCommand(command, null);
    }

    private Optional<Stream<String>> execCommand(@Nonnull String[] command, @Nullable Function<InputStream, Stream<String>> commandOutputProcessing) {
        ifThen(this.isVerbose, () -> System.out.println("Dplink: " + Arrays.stream(command).collect(Collectors.joining(" "))));
        try {
            Process commandProcess = Runtime.getRuntime().exec(command);
            commandProcess.waitFor(20, TimeUnit.MINUTES);
            try (InputStream commandInputStream = commandProcess.getInputStream()) {
                fatalGuard(commandProcess.exitValue() == 0, () -> {
                    BufferedReader jlinkReader = new BufferedReader(new InputStreamReader(commandInputStream));
                    jlinkReader.lines().forEach(System.err::println);
                    throw new RuntimeException("Command failed with exit code " + commandProcess.exitValue() + ": " +
                            Arrays.stream(command).collect(Collectors.joining(" ")));
                });

                return commandOutputProcessing != null ? Optional.of(commandOutputProcessing.apply(commandInputStream)) : Optional.empty();
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
