/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * User: anna
 * Date: 11-Nov-2008
 */
package org.jetbrains.idea.eclipse.conversion;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PathMacroManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.projectRoots.JavaSdkType;
import com.intellij.openapi.projectRoots.Sdk;
import com.intellij.openapi.roots.*;
import com.intellij.openapi.roots.impl.ProjectRootManagerImpl;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.CharFilter;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.vfs.ex.http.HttpFileSystem;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.eclipse.EclipseXml;
import org.jetbrains.idea.eclipse.IdeaXml;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class EclipseClasspathWriter {
  private ModifiableRootModel myModel;

  public EclipseClasspathWriter(final ModifiableRootModel model) {
    myModel = model;
  }

  public void writeClasspath(Element classpathElement, @Nullable Element oldRoot) throws ConversionException {
    for (OrderEntry orderEntry : myModel.getOrderEntries()) {
      createClasspathEntry(orderEntry, classpathElement, oldRoot);
    }

    @NonNls String outputPath = "bin";
    if (myModel.getContentEntries().length == 1) {
      final VirtualFile contentRoot = myModel.getContentEntries()[0].getFile();
      final VirtualFile output = myModel.getModuleExtension(CompilerModuleExtension.class).getCompilerOutputPath();
      if (contentRoot != null && output != null && VfsUtil.isAncestor(contentRoot, output, false)) {
        outputPath = getRelativePath(output.getUrl());
      }
      else if (output == null) {
        final String url = myModel.getModuleExtension(CompilerModuleExtension.class).getCompilerOutputUrl();
        if (url != null) {
          outputPath = getRelativePath(url);
        }
      }
    }
    final Element orderEntry = addOrderEntry(EclipseXml.OUTPUT_KIND, outputPath, classpathElement, oldRoot);
    setAttributeIfAbsent(orderEntry, EclipseXml.PATH_ATTR, EclipseXml.BIN_DIR);
  }

  private void createClasspathEntry(OrderEntry entry, Element classpathRoot, Element oldRoot) throws ConversionException {
    if (entry instanceof ModuleSourceOrderEntry) {
      final ContentEntry[] entries = ((ModuleSourceOrderEntry)entry).getRootModel().getContentEntries();
      if (entries.length > 0) {
        final ContentEntry contentEntry = entries[0];
        for (SourceFolder sourceFolder : contentEntry.getSourceFolders()) {
          addOrderEntry(EclipseXml.SRC_KIND, getRelativePath(sourceFolder.getUrl()), classpathRoot, oldRoot);
        }
      }
    }
    else if (entry instanceof ModuleOrderEntry) {
      Element orderEntry = addOrderEntry(EclipseXml.SRC_KIND, "/" + ((ModuleOrderEntry)entry).getModuleName(), classpathRoot, oldRoot);
      setAttributeIfAbsent(orderEntry, EclipseXml.COMBINEACCESSRULES_ATTR, EclipseXml.FALSE_VALUE);
      setExported(orderEntry, ((ExportableOrderEntry)entry));
    }
    else if (entry instanceof LibraryOrderEntry) {
      final LibraryOrderEntry libraryOrderEntry = (LibraryOrderEntry)entry;
      final String libraryName = libraryOrderEntry.getLibraryName();
      if (libraryOrderEntry.isModuleLevel()) {
        final String[] files = libraryOrderEntry.getUrls(OrderRootType.CLASSES);
        if (files.length > 0) {
          if (libraryName != null &&
              libraryName.contains(IdeaXml.JUNIT) &&
              Comparing.strEqual(files[0], EclipseClasspathReader.getJunitClsUrl(libraryName.contains("4")))) {
            final Element orderEntry =
              addOrderEntry(EclipseXml.CON_KIND, EclipseXml.JUNIT_CONTAINER + "/" + libraryName.substring(IdeaXml.JUNIT.length()),
                            classpathRoot, oldRoot);
            setExported(orderEntry, libraryOrderEntry);
          }
          else {
            final Project project = myModel.getModule().getProject();
            final String[] kind = new String[]{EclipseXml.LIB_KIND};
            String relativeClassPath = getRelativePath(files[0], kind);

            final String[] srcFiles = libraryOrderEntry.getUrls(OrderRootType.SOURCES);
            final String relativePath;
            if (srcFiles.length == 0) {
              relativePath = null;
            }
            else {
              final String[] srcKind = new String[1];
              final boolean replaceVarsInSrc = Comparing.strEqual(kind[0], EclipseXml.VAR_KIND);
              relativePath = getRelativePath(srcFiles[srcFiles.length - 1], srcKind, replaceVarsInSrc, project, getContentRoot());
              if (replaceVarsInSrc && srcKind[0] == null) {
                kind[0] = EclipseXml.LIB_KIND;
                relativeClassPath = getRelativePath(files[0], kind, false, project, getContentRoot());
              }
            }

            final Element orderEntry = addOrderEntry(kind[0], relativeClassPath, classpathRoot, oldRoot);
            setOrRemoveAttribute(orderEntry, EclipseXml.SOURCEPATH_ATTR, relativePath);

            //clear javadocs before write new
            final List children = new ArrayList(orderEntry.getChildren(EclipseXml.ATTRIBUTES_TAG));
            for (Object o : children) {
              ((Element)o).detach();
            }
            final String[] docUrls = libraryOrderEntry.getUrls(JavadocOrderRootType.getInstance());
            for (final String docUrl : docUrls) {
              setJavadocPath(orderEntry, docUrl);
            }

            setExported(orderEntry, libraryOrderEntry);
          }
        }
      }
      else {
        final Element orderEntry;
        if (Comparing.strEqual(libraryName, IdeaXml.ECLIPSE_LIBRARY)) {
          orderEntry = addOrderEntry(EclipseXml.CON_KIND, EclipseXml.ECLIPSE_PLATFORM, classpathRoot, oldRoot);
        }
        else {
          orderEntry = addOrderEntry(EclipseXml.CON_KIND, EclipseXml.USER_LIBRARY + "/" + libraryName, classpathRoot, oldRoot);
        }
        setExported(orderEntry, libraryOrderEntry);
      }
    }
    else if (entry instanceof JdkOrderEntry) {
      if (entry instanceof InheritedJdkOrderEntry) {
        addOrderEntry(EclipseXml.CON_KIND, EclipseXml.JRE_CONTAINER, classpathRoot, oldRoot);
      }
      else {
        final Sdk jdk = ((JdkOrderEntry)entry).getJdk();
        String jdkLink;
        if (jdk == null) {
          jdkLink = EclipseXml.JRE_CONTAINER;
        }
        else {
          jdkLink = EclipseXml.JRE_CONTAINER;
          if (jdk.getSdkType() instanceof JavaSdkType) {
            jdkLink += EclipseXml.JAVA_SDK_TYPE;
          }
          jdkLink += "/" + jdk.getName();
        }
        addOrderEntry(EclipseXml.CON_KIND, jdkLink, classpathRoot, oldRoot);
      }
    }
    else {
      throw new ConversionException("Unknown EclipseProjectModel.ClasspathEntry: " + entry.getClass());
    }
  }

  private String getRelativePath(String srcFile, String[] kind) {
    return getRelativePath(srcFile, kind, true, myModel.getModule().getProject(), getContentRoot());
  }

  private String getRelativePath(String url) {
    return getRelativePath(url, new String[1]);
  }

  public static String getRelativePath(final String url,
                                       String[] kind,
                                       boolean replaceVars,
                                       final Project project,
                                       final VirtualFile contentRoot) {
    final VirtualFile projectBaseDir = contentRoot != null ? contentRoot.getParent() : project.getBaseDir();
    assert projectBaseDir != null;
    VirtualFile file = VirtualFileManager.getInstance().findFileByUrl(url);
    if (file != null) {
      if (file.getFileSystem() instanceof JarFileSystem) {
        file = JarFileSystem.getInstance().getVirtualFileForJar(file);
      }
      assert file != null;
      if (contentRoot != null) {
        if (VfsUtil.isAncestor(contentRoot, file, false)) {
          return VfsUtil.getRelativePath(file, contentRoot, '/');
        } else {
          final Module module = ModuleUtil.findModuleForFile(file, project);
          if (module != null) {
            final VirtualFile[] contentRoots = ModuleRootManager.getInstance(module).getContentRoots();
            for (VirtualFile otherRoot : contentRoots) {
              if (VfsUtil.isAncestor(otherRoot, file, false)) {
                return "/" + module.getName() + "/" + VfsUtil.getRelativePath(file, otherRoot, '/');
              }
            }
          }
        }
      }
      if (VfsUtil.isAncestor(projectBaseDir, file, false)) {
        return "/" + VfsUtil.getRelativePath(file, projectBaseDir, '/');
      }
      else {
        return replaceVars ? stripIDEASpecificPrefix(url, kind) : ProjectRootManagerImpl.extractLocalPath(url);
      }
    }
    else {
      if (contentRoot != null) {
        final String rootUrl = contentRoot.getUrl();
        if (url.startsWith(rootUrl) && url.length() > rootUrl.length()) {
          return url.substring(rootUrl.length() + 1); //without leading /
        }
      }
      final String projectUrl = projectBaseDir.getUrl();
      if (url.startsWith(projectUrl)) {
        return url.substring(projectUrl.length()); //leading /
      }

      return replaceVars ? stripIDEASpecificPrefix(url, kind) : ProjectRootManagerImpl.extractLocalPath(url);
    }
  }

  @Nullable
  private VirtualFile getContentRoot() {
    final ContentEntry[] entries = myModel.getContentEntries();
    if (entries.length > 0) {
      return entries[0].getFile();
    }
    return null;
  }

  private void setJavadocPath(final Element element, String javadocPath) {
    if (javadocPath != null) {
      Element child = new Element(EclipseXml.ATTRIBUTES_TAG);
      element.addContent(child);

      Element attrElement = child.getChild(EclipseXml.ATTRIBUTE_TAG);
      if (attrElement == null) {
        attrElement = new Element(EclipseXml.ATTRIBUTE_TAG);
        child.addContent(attrElement);
      }

      attrElement.setAttribute("name", "javadoc_location");

      final String protocol = VirtualFileManager.extractProtocol(javadocPath);
      if (!Comparing.strEqual(protocol, HttpFileSystem.getInstance().getProtocol())) {
        final String path = VfsUtil.urlToPath(javadocPath);
        final VirtualFile contentRoot = getContentRoot();
        final VirtualFile baseDir = contentRoot != null ? contentRoot.getParent() : myModel.getProject().getBaseDir();
        if (Comparing.strEqual(protocol, JarFileSystem.getInstance().getProtocol())) {
          final VirtualFile javadocFile =
            JarFileSystem.getInstance().getVirtualFileForJar(VirtualFileManager.getInstance().findFileByUrl(javadocPath));
          if (javadocFile != null && VfsUtil.isAncestor(baseDir, javadocFile, false)) {
            if (javadocPath.indexOf(JarFileSystem.JAR_SEPARATOR) == -1) {
              javadocPath = StringUtil.trimEnd(javadocPath, "/") + JarFileSystem.JAR_SEPARATOR;
            }
            javadocPath = EclipseXml.JAR_PREFIX +
                          EclipseXml.PLATFORM_PROTOCOL +
                          "resource/" +
                          VfsUtil.getRelativePath(javadocFile, baseDir, '/') +
                          javadocPath.substring(javadocFile.getUrl().length() - 1);
          }
          else {
            javadocPath = EclipseXml.JAR_PREFIX + EclipseXml.FILE_PROTOCOL + StringUtil.trimStart(path, "/");
          }
        }
        else if (new File(path).exists()) {
          javadocPath = EclipseXml.FILE_PROTOCOL + StringUtil.trimStart(path, "/");
        }
      }

      attrElement.setAttribute("value", javadocPath);
    }
  }

  private static String stripIDEASpecificPrefix(String path, String[] kind) {
    String stripped = StringUtil
      .strip(ProjectRootManagerImpl.extractLocalPath(PathMacroManager.getInstance(ApplicationManager.getApplication()).collapsePath(path)),
             new CharFilter() {
               public boolean accept(final char ch) {
                 return ch != '$';
               }
             });
    boolean leaveLeadingSlash = false;
    if (!Comparing.strEqual(stripped, ProjectRootManagerImpl.extractLocalPath(path))) {
      leaveLeadingSlash = kind[0] == null;
      kind[0] = EclipseXml.VAR_KIND;
    }
    return (leaveLeadingSlash ? "/" : "") + stripped;
  }


  private static Element addOrderEntry(String kind, String path, Element classpathRoot, Element oldRoot) {
    if (oldRoot != null) {
      for (Object o : oldRoot.getChildren(EclipseXml.CLASSPATHENTRY_TAG)) {
        final Element oldChild = (Element)o;
        final String oldKind = oldChild.getAttributeValue(EclipseXml.KIND_ATTR);
        final String oldPath = oldChild.getAttributeValue(EclipseXml.PATH_ATTR);
        if (Comparing.strEqual(kind, oldKind) && Comparing.strEqual(path, oldPath)) {
          final Element element = (Element)oldChild.clone();
          classpathRoot.addContent(element);
          return element;
        }
      }
    }
    Element orderEntry = new Element(EclipseXml.CLASSPATHENTRY_TAG);
    orderEntry.setAttribute(EclipseXml.KIND_ATTR, kind);
    if (path != null) {
      orderEntry.setAttribute(EclipseXml.PATH_ATTR, path);
    }
    classpathRoot.addContent(orderEntry);
    return orderEntry;
  }

  private static void setExported(Element orderEntry, ExportableOrderEntry dependency) {
    setOrRemoveAttribute(orderEntry, EclipseXml.EXPORTED_ATTR, dependency.isExported() ? EclipseXml.TRUE_VALUE : null);
  }

  private static void setOrRemoveAttribute(Element element, String name, String value) {
    if (value != null) {
      element.setAttribute(name, value);
    }
    else {
      element.removeAttribute(name);
    }
  }

  private static void setAttributeIfAbsent(Element element, String name, String value) {
    if (element.getAttribute(name) == null) {
      element.setAttribute(name, value);
    }
  }

}
