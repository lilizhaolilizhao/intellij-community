package com.intellij.psi.impl.cache.impl;

import com.intellij.ExtensionPoints;
import com.intellij.ide.startup.FileContent;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.impl.LoadTextUtil;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.PsiFileEx;
import com.intellij.psi.search.IndexPatternProvider;

import java.io.IOException;

public class CacheUtil {
  public static final Key<Boolean> CACHE_COPY_KEY = new Key<Boolean>("CACHE_COPY_KEY");

  private CacheUtil() {
  }

  public static PsiFile createFileCopy(PsiFile psiFile) {
    return createFileCopy(null, psiFile);
  }

  public static boolean isCopy(PsiFile psiFile) {
    return psiFile.getUserData(CACHE_COPY_KEY) != null;
  }

  public static PsiFile createFileCopy(FileContent content, PsiFile psiFile) {
    VirtualFile vFile = psiFile.getVirtualFile();
    if (vFile == null) return psiFile; // It's already a copy created via PsiManager.getFile(FileContent). Usually happens on initial startup.

    if (psiFile instanceof PsiFileEx) {
      return ((PsiFileEx)psiFile).cacheCopy(content);
    }

    return psiFile;
  }

  private static final Key<CharSequence> CONTENT_KEY = new Key<CharSequence>("CONTENT_KEY");

  public static CharSequence getContentText(final FileContent content) {
    final Document doc = FileDocumentManager.getInstance().getCachedDocument(content.getVirtualFile());
    if (doc != null) return doc.getCharsSequence();

    CharSequence cached = content.getUserData(CONTENT_KEY);
    if (cached != null) return cached;
    try {
      cached = LoadTextUtil.getTextByBinaryPresentation(content.getBytes(), content.getVirtualFile(), false);
      cached = content.putUserDataIfAbsent(CONTENT_KEY, cached);
      return cached;
    }
    catch (IOException e) {
      return "";
    }
  }

  public static IndexPatternProvider[] getIndexPatternProviders() {
    return (IndexPatternProvider[]) Extensions.getRootArea().getExtensionPoint(ExtensionPoints.INDEX_PATTERN_PROVIDER).getExtensions();
  }
}
