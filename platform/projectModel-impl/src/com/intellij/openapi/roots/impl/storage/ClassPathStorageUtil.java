/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.roots.impl.storage;

import com.intellij.openapi.module.Module;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.model.serialization.JpsProjectLoader;

public class ClassPathStorageUtil {
  @NonNls public static final String DEFAULT_STORAGE = "default";

  @NotNull
  public static String getStorageType(@NotNull Module module) {
    String id = module.getOptionValue(JpsProjectLoader.CLASSPATH_ATTRIBUTE);
    return id == null ? DEFAULT_STORAGE : id;
  }

  public static boolean isClasspathStorage(@NotNull Module module) {
    return module.getOptionValue(JpsProjectLoader.CLASSPATH_ATTRIBUTE) != null;
  }
}
