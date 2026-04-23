/*
 * Copyright (c) 2022 - present. New Relic Corporation. All rights reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package com.newrelic.agent.android

import com.newrelic.agent.InstrumentationAgent
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

@CacheableTask
abstract class NewRelicConfigTask extends DefaultTask {
    final static String NAME = "newrelicConfig"
    final static String CONFIG_CLASS_NAME = "com/newrelic/agent/android/NewRelicConfig"
    final static String METADATA = ".metadata"

    @Input
    abstract Property<String> getBuildId()      // variant buildId

    @Input
    abstract Property<String> getMapProvider()  // [proguard, r8, dexguard]

    @Input
    abstract Property<Boolean> getMinifyEnabled()

    @Input
    @Optional
    abstract Property<String> getBuildMetrics()

    @OutputFile
    abstract RegularFileProperty getOutputJar()

    @OutputFile
    @Optional
    abstract RegularFileProperty getConfigMetadata()

    @TaskAction
    def newRelicConfigTask() {
        try {
            def classBytes = generateConfigClass(
                    InstrumentationAgent.getVersion(),
                    buildId.get(),
                    minifyEnabled.getOrElse(false),
                    mapProvider.get(),
                    buildMetrics.getOrElse("")
            )

            def jarFile = outputJar.get().asFile
            jarFile.parentFile.mkdirs()

            new JarOutputStream(new FileOutputStream(jarFile)).withCloseable { jar ->
                jar.putNextEntry(new JarEntry("${CONFIG_CLASS_NAME}.class"))
                jar.write(classBytes)
                jar.closeEntry()
            }

            def metaFile = configMetadata.get().asFile
            metaFile.parentFile.mkdirs()
            metaFile.text = buildId.get()

        } catch (Exception e) {
            logger.error("Error encountered while configuring the New Relic plugin: ", e)
        }
    }

    /**
     * Generate bytecode for NewRelicConfig.class using ASM.
     *
     * Produces the equivalent of:
     * <pre>
     * package com.newrelic.agent.android;
     * final class NewRelicConfig {
     *     static final String VERSION = "...";
     *     static final String BUILD_ID = "...";
     *     static final Boolean OBFUSCATED = true/false;
     *     static final String MAP_PROVIDER = "...";
     *     static final String METRICS = "...";
     *     public static String getBuildId() { return BUILD_ID; }
     * }
     * </pre>
     */
    private static byte[] generateConfigClass(
            String version, String buildIdValue, boolean obfuscated,
            String mapProvider, String metrics) {

        def cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES)

        // class NewRelicConfig (package-private, final)
        cw.visit(Opcodes.V11, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER,
                CONFIG_CLASS_NAME, null, "java/lang/Object", null)

        // static final String fields — stored as ConstantValue attributes
        cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "VERSION", "Ljava/lang/String;", null, version).visitEnd()
        cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "BUILD_ID", "Ljava/lang/String;", null, buildIdValue).visitEnd()
        cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "OBFUSCATED", "Ljava/lang/Boolean;", null, null).visitEnd()
        cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "MAP_PROVIDER", "Ljava/lang/String;", null, mapProvider).visitEnd()
        cw.visitField(Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "METRICS", "Ljava/lang/String;", null, metrics).visitEnd()

        // <clinit> — initialize the Boolean OBFUSCATED field
        MethodVisitor clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null)
        clinit.visitCode()
        clinit.visitInsn(obfuscated ? Opcodes.ICONST_1 : Opcodes.ICONST_0)
        clinit.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
                "(Z)Ljava/lang/Boolean;", false)
        clinit.visitFieldInsn(Opcodes.PUTSTATIC, CONFIG_CLASS_NAME,
                "OBFUSCATED", "Ljava/lang/Boolean;")
        clinit.visitInsn(Opcodes.RETURN)
        clinit.visitMaxs(1, 0)
        clinit.visitEnd()

        // <init> — package-private constructor
        MethodVisitor init = cw.visitMethod(0, "<init>", "()V", null, null)
        init.visitCode()
        init.visitVarInsn(Opcodes.ALOAD, 0)
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        init.visitInsn(Opcodes.RETURN)
        init.visitMaxs(1, 1)
        init.visitEnd()

        // public static String getBuildId()
        MethodVisitor getBuildId = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getBuildId", "()Ljava/lang/String;", null, null)
        getBuildId.visitCode()
        getBuildId.visitFieldInsn(Opcodes.GETSTATIC, CONFIG_CLASS_NAME,
                "BUILD_ID", "Ljava/lang/String;")
        getBuildId.visitInsn(Opcodes.ARETURN)
        getBuildId.visitMaxs(1, 0)
        getBuildId.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    @Internal
    @Override
    Logger getLogger() {
        return NewRelicGradlePlugin.LOGGER
    }

}
