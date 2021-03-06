// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.configurationStore

import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationNamesInfo
import com.intellij.openapi.application.PathMacros
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.command.impl.UndoManagerImpl
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.ComponentManager
import com.intellij.openapi.components.TrackingPathMacroSubstitutor
import com.intellij.openapi.components.impl.stores.IComponentStore
import com.intellij.openapi.components.impl.stores.UnknownMacroNotification
import com.intellij.openapi.components.stateStore
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectBundle
import com.intellij.openapi.project.impl.ProjectMacrosUtil
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.io.createDirectories
import com.intellij.util.io.inputStream
import com.intellij.util.io.systemIndependentPath
import gnu.trove.THashSet
import org.jetbrains.annotations.TestOnly
import java.io.IOException
import java.nio.file.Path
import java.util.*

const val NOTIFICATION_GROUP_ID = "Load Error"

@TestOnly
var DEBUG_LOG: String? = null

fun doNotify(macros: MutableSet<String>, project: Project, substitutorToStore: Map<TrackingPathMacroSubstitutor, IComponentStore>) {
  val productName = ApplicationNamesInfo.getInstance().productName
  val content = "<p><i>${macros.joinToString(", ")}</i> ${if (macros.size == 1) "is" else "are"} undefined. <a href=\"define\">Fix it</a></p>" +
                "<br>Path variables are used to substitute absolute paths in " + productName + " project files " +
                "and allow project file sharing in version control systems.<br>" +
                "Some of the files describing the current project settings contain unknown path variables " +
                "and " + productName + " cannot restore those paths."
  UnknownMacroNotification(NOTIFICATION_GROUP_ID, "Load error: undefined path variables", content, NotificationType.ERROR,
                           { _, _ -> checkUnknownMacros(project, true, macros, substitutorToStore) }, macros)
    .notify(project)
}

fun checkUnknownMacros(project: Project, notify: Boolean) {
  // use linked set/map to get stable results
  val unknownMacros = LinkedHashSet<String>()
  val substitutorToStore = LinkedHashMap<TrackingPathMacroSubstitutor, IComponentStore>()
  collect(project, unknownMacros, substitutorToStore)
  for (module in ModuleManager.getInstance(project).modules) {
    collect(module, unknownMacros, substitutorToStore)
  }

  if (unknownMacros.isEmpty()) {
    return
  }

  if (notify) {
    doNotify(unknownMacros, project, substitutorToStore)
    return
  }

  checkUnknownMacros(project, false, unknownMacros, substitutorToStore)
}

private fun checkUnknownMacros(project: Project,
                               showDialog: Boolean,
                               unknownMacros: MutableSet<String>,
                               substitutorToStore: Map<TrackingPathMacroSubstitutor, IComponentStore>) {
  if (unknownMacros.isEmpty() || (showDialog && !ProjectMacrosUtil.checkMacros(project, THashSet(unknownMacros)))) {
    return
  }

  val pathMacros = PathMacros.getInstance()
  unknownMacros.removeAll { pathMacros.getValue(it).isNullOrBlank() && !pathMacros.isIgnoredMacroName(it) }
  if (unknownMacros.isEmpty()) {
    return
  }

  val notificationManager = NotificationsManager.getNotificationsManager()
  for ((substitutor, store) in substitutorToStore) {
    val components = substitutor.getComponents(unknownMacros)
    if (store.isReloadPossible(components)) {
      substitutor.invalidateUnknownMacros(unknownMacros)

      for (notification in notificationManager.getNotificationsOfType(UnknownMacroNotification::class.java, project)) {
        if (unknownMacros.containsAll(notification.macros)) {
          notification.expire()
        }
      }

      store.reloadStates(components, project.messageBus)
    }
    else if (Messages.showYesNoDialog(project, "Component could not be reloaded. Reload project?", "Configuration Changed",
                                      Messages.getQuestionIcon()) == Messages.YES) {
      StoreReloadManager.getInstance().reloadProject(project)
    }
  }
}

private fun collect(componentManager: ComponentManager,
                    unknownMacros: MutableSet<String>,
                    substitutorToStore: MutableMap<TrackingPathMacroSubstitutor, IComponentStore>) {
  val store = componentManager.stateStore
  val substitutor = store.storageManager.macroSubstitutor as? TrackingPathMacroSubstitutor ?: return
  val macros = substitutor.getUnknownMacros(null)
  if (macros.isEmpty()) {
    return
  }

  unknownMacros.addAll(macros)
  substitutorToStore.put(substitutor, store)
}

fun getOrCreateVirtualFile(file: Path, requestor: Any?): VirtualFile {
  val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(file.systemIndependentPath)
  if (virtualFile != null) {
    return virtualFile
  }

  val parentFile = file.parent
  parentFile.createDirectories()

  // need refresh if the directory has just been created
  val parentVirtualFile = LocalFileSystem.getInstance().refreshAndFindFileByPath(parentFile.systemIndependentPath)
                          ?: throw IOException(ProjectBundle.message("project.configuration.save.file.not.found", parentFile))

  return runNonUndoableAction(parentVirtualFile) {
    runAsWriteActionIfNeeded {
      parentVirtualFile.createChildData(requestor, file.fileName.toString())
    }
  }
}

// runWriteAction itself cannot do such check because in general case any write action must be tracked regardless of current action
inline fun <T> runAsWriteActionIfNeeded(crossinline runnable: () -> T): T {
  return when {
    ApplicationManager.getApplication().isWriteAccessAllowed -> runnable()
    else -> runWriteAction(runnable)
  }
}

@Throws(IOException::class)
fun readProjectNameFile(nameFile: Path): String? {
  return nameFile.inputStream().reader().useLines { line -> line.firstOrNull { !it.isEmpty() }?.trim() }
}

inline fun <T> runNonUndoableAction(file: VirtualFile, task: () -> T): T {
  // must be before task (maybe renamed or deleted)
  val documentReference = DocumentReferenceManager.getInstance().create(file)
  val result = task()
  val undoManager = UndoManager.getGlobalInstance() as UndoManagerImpl
  undoManager.nonundoableActionPerformed(documentReference, true)
  undoManager.invalidateActionsFor(documentReference)
  return result
}

inline fun <T> runNonUndoableWriteAction(file: VirtualFile, crossinline task: () -> T): T {
  return runNonUndoableAction(file) {
    runWriteAction(task)
  }
}