package org.cjc.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 在 generate-sources 阶段把中文源码转译到 target/generated-sources/cjc，
 * 并把该目录加入编译源根；后续 javac 编译的是转译后的英文源码。
 *
 * 中文源码目录默认为 src/main/中文，因此不会与 src/main/java 中的英文源码冲突。
 */
@Mojo(name = "translate", defaultPhase = LifecyclePhase.GENERATE_SOURCES, threadSafe = true)
public class TranslateMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /** 中文源码目录 */
    @Parameter(property = "cjc.sourceDirectory", defaultValue = "${project.basedir}/src/main/中文")
    private File sourceDirectory;

    /** 转译结果输出目录 */
    @Parameter(property = "cjc.outputDirectory", defaultValue = "${project.build.directory}/generated-sources/cjc")
    private File outputDirectory;

    /** Cjc.java 路径 */
    @Parameter(property = "cjc.java", defaultValue = "${project.basedir}/工具/cjc/Cjc.java")
    private File cjcJava;

    /** 关键字表路径 */
    @Parameter(property = "cjc.table", defaultValue = "${project.basedir}/工具/cjc/关键字表.txt")
    private File table;

    @Parameter(property = "cjc.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (skip) {
            getLog().info("cjc.skip=true，跳过中文源码转译");
            return;
        }
        if (!sourceDirectory.isDirectory()) {
            getLog().info("未找到中文源码目录 " + sourceDirectory + "，跳过转译");
            return;
        }
        if (!cjcJava.isFile()) {
            throw new MojoExecutionException("找不到 Cjc.java：" + cjcJava
                    + "（可用 -Dcjc.java=<路径> 指定）");
        }
        if (!table.isFile()) {
            throw new MojoExecutionException("找不到关键字表：" + table
                    + "（可用 -Dcjc.table=<路径> 指定）");
        }

        Path out = outputDirectory.toPath().toAbsolutePath().normalize();
        try {
            if (Files.exists(out)) {
                deleteRecursively(out);
            }
            Files.createDirectories(out);
        } catch (IOException e) {
            throw new MojoExecutionException("无法准备输出目录 " + out, e);
        }

        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.add(cjcJava.getAbsolutePath());
        command.add("转译");
        command.add(sourceDirectory.getAbsolutePath());
        command.add("-d");
        command.add(out.toString());
        command.add("--表");
        command.add(table.getAbsolutePath());

        getLog().info("转译中文源码：" + sourceDirectory + " → " + out);
        int exitCode;
        try {
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            exitCode = process.waitFor();
            for (String line : output.split("\\R")) {
                if (!line.isEmpty()) {
                    getLog().info("  " + line);
                }
            }
        } catch (IOException e) {
            throw new MojoExecutionException("启动 cjc 转译进程失败：" + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("cjc 转译被中断", e);
        }
        if (exitCode != 0) {
            throw new MojoFailureException("中文源码转译失败，退出码 " + exitCode + "，详见上方 CJK 诊断");
        }
        project.addCompileSourceRoot(out.toString());
        getLog().info("已把 " + out + " 加入编译源根");
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", name).toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }
}
