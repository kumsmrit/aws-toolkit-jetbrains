// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0
@file:JvmName("SamInitProjectBuilderCommon")

package software.aws.toolkits.jetbrains.services.lambda.wizard

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runInEdt
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DefaultProjectFactory
import software.aws.toolkits.jetbrains.core.executables.ExecutableInstance
import software.aws.toolkits.jetbrains.core.executables.ExecutableManager
import software.aws.toolkits.jetbrains.core.executables.getExecutable
import software.aws.toolkits.jetbrains.core.executables.getExecutableIfPresent
import software.aws.toolkits.jetbrains.services.lambda.sam.SamExecutable
import software.aws.toolkits.jetbrains.settings.ToolkitSettingsConfigurable
import software.aws.toolkits.resources.message
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JTextField

@JvmOverloads
fun setupSamSelectionElements(samExecutableField: JTextField, editButton: JButton, label: JComponent, postEditCallback: Runnable? = null) {
    fun getSamExecutable(): ExecutableInstance.ExecutableWithPath? =
        ExecutableManager.getInstance().getExecutableIfPresent<SamExecutable>().let { it as? ExecutableInstance.ExecutableWithPath }

    fun updateUi(validSamPath: Boolean) {
        runInEdt(ModalityState.any()) {
            samExecutableField.isVisible = !validSamPath
            editButton.isVisible = !validSamPath
            label.isVisible = !validSamPath
        }
    }

    samExecutableField.text = getSamExecutable()?.executablePath?.toString()

    editButton.addActionListener {
        ShowSettingsUtil.getInstance().showSettingsDialog(DefaultProjectFactory.getInstance().defaultProject, ToolkitSettingsConfigurable::class.java)
        samExecutableField.text = getSamExecutable()?.executablePath?.toString()
        postEditCallback?.run()
    }

    val toolTipText = message("aws.settings.find.description", "SAM")
    label.toolTipText = toolTipText
    samExecutableField.toolTipText = toolTipText
    editButton.toolTipText = toolTipText

    ProgressManager.getInstance().runProcessWithProgressSynchronously(
        {
            try {
                val validSamPath = when (ExecutableManager.getInstance().getExecutable<SamExecutable>().toCompletableFuture().get()) {
                    is ExecutableInstance.Executable -> true
                    else -> false
                }
                updateUi(validSamPath)
            } catch (e: Throwable) {
                updateUi(validSamPath = false)
            }
        },
        message("lambda.run_configuration.sam.validating"),
        false,
        null
    )
}
