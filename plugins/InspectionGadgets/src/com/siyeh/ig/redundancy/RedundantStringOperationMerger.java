// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.siyeh.ig.redundancy;

import com.google.common.collect.ImmutableSet;
import com.intellij.codeInspection.ex.InspectionElementsMergerBase;
import com.intellij.util.ArrayUtil;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.Set;

public class RedundantStringOperationMerger extends InspectionElementsMergerBase {

  private static final String OLD_MERGER_NAME = "RedundantStringOperation";
  private static final Set<String> OLD_SOURCE_NAMES = ImmutableSet.of("StringToString", "SubstringZero", "ConstantStringIntern");

  @NotNull
  @Override
  public String getMergedToolName() {
    return "StringOperationCanBeSimplified";
  }

  @Override
  protected Element getSourceElement(Map<String, Element> inspectionElements, String sourceToolName) {
    if (inspectionElements.containsKey(sourceToolName)) {
      return inspectionElements.get(sourceToolName);
    }

    if (sourceToolName.equals(OLD_MERGER_NAME)) {//need to merge initial tools to get merged redundant string operations
      return new InspectionElementsMergerBase(){
        @NotNull
        @Override
        public String getMergedToolName() {
          return OLD_MERGER_NAME;
        }

        @NotNull
        @Override
        public String[] getSourceToolNames() {
          return ArrayUtil.toStringArray(OLD_SOURCE_NAMES);
        }

        @Override
        public Element merge(Map<String, Element> inspectionElements) {
          return super.merge(inspectionElements);
        }

        @Override
        protected boolean writeMergedContent(Element toolElement) {
          return true;
        }
      }.merge(inspectionElements);
    }
    else if (OLD_SOURCE_NAMES.contains(sourceToolName)) {
      Element merged = inspectionElements.get(OLD_MERGER_NAME);
      if (merged != null) { // RedundantStringOperation already replaced the content
        Element clone = merged.clone();
        clone.setAttribute("class", sourceToolName);
        return clone;
      }
    }
    return null;
  }

  @NotNull
  @Override
  public String[] getSourceToolNames() {
    return new String[] {
      "StringToString",
      "SubstringZero", 
      "ConstantStringIntern",
      "StringConstructor",
      OLD_MERGER_NAME
    };
  }

  @NotNull
  @Override
  public String[] getSuppressIds() {
    return new String[] {
      "StringToString", "RedundantStringToString",
      "SubstringZero", "ConstantStringIntern",
      "RedundantStringConstructorCall", "StringConstructor", OLD_MERGER_NAME
    };
  }
}
