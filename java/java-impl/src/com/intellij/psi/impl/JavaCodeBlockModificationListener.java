// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.psi.impl;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiTreeChangeEventImpl.PsiEventType;
import com.intellij.psi.impl.source.jsp.jspXml.JspDirective;
import com.intellij.psi.impl.source.tree.TreeUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Set;

final class JavaCodeBlockModificationListener extends PsiTreeChangePreprocessorBase {
  JavaCodeBlockModificationListener(@NotNull Project project) {
    super(project);
  }

  @Override
  protected boolean acceptsEvent(@NotNull PsiTreeChangeEventImpl event) {
    return event.getFile() instanceof PsiClassOwner;
  }

  @Override
  protected boolean isOutOfCodeBlock(@NotNull PsiElement element) {
    for (PsiElement e : SyntaxTraverser.psiApi().parents(element)) {
      if (e instanceof PsiModifiableCodeBlock) {
        // trigger OOCBM for final variables initialized in constructors & class initializers
        if (!((PsiModifiableCodeBlock)e).shouldChangeModificationCount(element)) return false;
      }
      if (e instanceof PsiClass) break;
      if (e instanceof PsiClassOwner || e instanceof JspDirective) break;
    }
    return true;
  }

  @Override
  protected boolean isOutOfCodeBlock(@NotNull PsiFileSystemItem file) {
    if (file instanceof PsiModifiableCodeBlock) {
      return ((PsiModifiableCodeBlock)file).shouldChangeModificationCount(file);
    }
    return super.isOutOfCodeBlock(file);
  }

  @Override
  protected boolean containsStructuralElements(@NotNull PsiElement element) {
    return mayHaveModifiedJavaStructureInside(element);
  }

  @Override
  protected void onTreeChanged(@NotNull PsiTreeChangeEventImpl event) {
    Set<PsiElement> changedChildren = getChangedChildren(event);

    PsiModificationTrackerImpl tracker = (PsiModificationTrackerImpl)myPsiManager.getModificationTracker();
    if (!changedChildren.isEmpty() && changedChildren.stream().anyMatch(JavaCodeBlockModificationListener::mayHaveModifiedJavaStructureInside)) {
      tracker.incCounter();
    }

    if (isOutOfCodeBlockChangeEvent(event)) {
      if (changedChildren.isEmpty() || changedChildren.stream().anyMatch(e -> !isWhiteSpaceOrComment(e))) {
        tracker.incCounter(); // java structure change
      }
      else {
        tracker.incOutOfCodeBlockModificationCounter();
      }
    }
  }

  private static boolean isWhiteSpaceOrComment(@NotNull PsiElement e) {
    return e instanceof PsiWhiteSpace || PsiTreeUtil.getParentOfType(e, PsiComment.class, false) != null;
  }

  private static Set<PsiElement> getChangedChildren(@NotNull PsiTreeChangeEventImpl event) {
    PsiEventType code = event.getCode();
    if (code == PsiEventType.CHILD_ADDED || code == PsiEventType.CHILD_REMOVED || code == PsiEventType.CHILD_REPLACED) {
      return StreamEx.of(event.getOldChild(), event.getChild(), event.getNewChild()).nonNull().toSet();
    }
    if (code == PsiEventType.BEFORE_CHILD_REMOVAL || code == PsiEventType.BEFORE_CHILD_REPLACEMENT) {
      return StreamEx.of(event.getOldChild(), event.getChild()).nonNull().toSet();
    }
    if (code == PsiEventType.BEFORE_CHILDREN_CHANGE && !event.isGenericChange()) {
      PsiElement parent = event.getParent();
      if (!(parent instanceof PsiFileSystemItem) && !TreeUtil.isCollapsedChameleon(parent.getNode())) {
        return ContainerUtil.newHashSet(parent.getChildren());
      }
    }
    return Collections.emptySet();
  }

  private static boolean mayHaveModifiedJavaStructureInside(@NotNull PsiElement root) {
    return SyntaxTraverser.psiTraverser(root)
      .expand(e -> !TreeUtil.isCollapsedChameleon(e.getNode()))
      .traverse()
      .filter(e -> !(e instanceof PsiModifiableCodeBlock) || ((PsiModifiableCodeBlock)e).shouldChangeModificationCount(e))
      .filter(e -> e instanceof PsiClass || e instanceof PsiLambdaExpression || TreeUtil.isCollapsedChameleon(e.getNode()))
      .isNotEmpty();
  }
}
