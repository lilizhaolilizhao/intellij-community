package org.jetbrains.plugins.gradle.remote.impl;

import com.intellij.execution.rmi.RemoteObject;
import com.intellij.openapi.roots.DependencyScope;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.util.containers.HashMap;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.DomainObjectSet;
import org.gradle.tooling.model.idea.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.importing.model.*;
import org.jetbrains.plugins.gradle.importing.model.impl.*;
import org.jetbrains.plugins.gradle.remote.GradleProjectResolver;
import org.jetbrains.plugins.gradle.remote.RemoteGradleProcessSettings;
import org.jetbrains.plugins.gradle.remote.RemoteGradleService;
import org.jetbrains.plugins.gradle.util.GradleBundle;
import org.jetbrains.plugins.gradle.util.GradleLog;

import java.io.File;
import java.rmi.RemoteException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author Denis Zhdanov
 * @since 8/8/11 11:09 AM
 */
public class GradleProjectResolverImpl extends RemoteObject implements GradleProjectResolver, RemoteGradleService {

  private final BlockingQueue<ProjectConnection>             myConnections = new LinkedBlockingQueue<ProjectConnection>();
  private final AtomicReference<RemoteGradleProcessSettings> mySettings    = new AtomicReference<RemoteGradleProcessSettings>();
  
  @NotNull
  @Override
  public GradleProject resolveProjectInfo(@NotNull String projectPath) throws RemoteException {
    try {
      return doResolve(projectPath);
    }
    catch (Throwable e) {
      throw new IllegalStateException(GradleBundle.message("gradle.import.text.error.resolve.generic", projectPath), e);
    }
  }

  @NotNull
  private GradleProject doResolve(@NotNull String projectPath) {
    ProjectConnection connection = getConnection(projectPath);
    IdeaProject project = connection.getModel(IdeaProject.class);
    // TODO den use OfflineIdeaProject as soon as gradle guys provide support for non-resolved libraries there.
    //OfflineIdeaProject project = connection.getModel(OfflineIdeaProject.class);
    GradleProjectImpl result = populateProject(project, projectPath);
    
    // We need two different steps ('create' and 'populate') in order to handle module dependencies, i.e. when one module is
    // configured to be dependency for another one, corresponding dependency module object should be available during
    // populating dependent module object.
    Map<String, Pair<GradleModuleImpl, IdeaModule>> modules = createModules(project, result);
    populateModules(modules.values(), result);
    return result;
  }

  private static GradleProjectImpl populateProject(@NotNull IdeaProject project, @NotNull String projectPath) {
    // TODO den retrieve the value from the gradle api as soon as it's ready
    GradleProjectImpl result = new GradleProjectImpl(new File(projectPath).getParentFile().getAbsolutePath() + "/out");
    result.setName(project.getName());
    result.setJdk(project.getJdkName());
    result.setLanguageLevel(project.getLanguageLevel().getLevel());
    return result;
  }

  @NotNull
  private static Map<String, Pair<GradleModuleImpl, IdeaModule>> createModules(@NotNull IdeaProject gradleProject,
                                                                           @NotNull GradleProjectImpl intellijProject)
    throws IllegalStateException
  {
    DomainObjectSet<? extends IdeaModule> gradleModules = gradleProject.getModules();
    if (gradleModules == null || gradleModules.isEmpty()) {
      throw new IllegalStateException("No modules found for the target project: " + gradleProject);
    }
    Map<String, Pair<GradleModuleImpl, IdeaModule>> result = new HashMap<String, Pair<GradleModuleImpl, IdeaModule>>();
    for (IdeaModule gradleModule : gradleModules) {
      if (gradleModule == null) {
        continue;
      }
      String moduleName = gradleModule.getName();
      if (moduleName == null) {
        throw new IllegalStateException("Module with undefined name detected: " + gradleModule);
      }
      GradleModuleImpl intellijModule = new GradleModuleImpl(moduleName);
      Pair<GradleModuleImpl, IdeaModule> previouslyParsedModule = result.get(moduleName);
      if (previouslyParsedModule != null) {
        throw new IllegalStateException(
          String.format("Modules with duplicate name (%s) detected: '%s' and '%s'", moduleName, intellijModule, previouslyParsedModule)
        );
      }
      result.put(moduleName, new Pair<GradleModuleImpl, IdeaModule>(intellijModule, gradleModule));
      intellijProject.addModule(intellijModule);
    }
    return result;
  }

  private static void populateModules(@NotNull Iterable<Pair<GradleModuleImpl, IdeaModule>> modules, 
                                      @NotNull GradleProject intellijProject)
    throws IllegalStateException
  {
    for (Pair<GradleModuleImpl, IdeaModule> pair : modules) {
      populateModule(pair.second, pair.first, intellijProject);
    }
  }

  private static void populateModule(@NotNull IdeaModule gradleModule, @NotNull GradleModuleImpl intellijModule,
                                     @NotNull GradleProject intellijProject)
    throws IllegalStateException
  {
    populateContentRoots(gradleModule, intellijModule);
    populateCompileOutputSettings(gradleModule.getCompilerOutput(), intellijModule);
    populateDependencies(gradleModule, intellijModule, intellijProject);
  }

  private static void populateContentRoots(@NotNull IdeaModule gradleModule, @NotNull GradleModuleImpl intellijModule) {
    DomainObjectSet<? extends IdeaContentRoot> contentRoots = gradleModule.getContentRoots();
    if (contentRoots == null) {
      return;
    }
    for (IdeaContentRoot gradleContentRoot : contentRoots) {
      if (gradleContentRoot == null) {
        continue;
      }
      File rootDirectory = gradleContentRoot.getRootDirectory();
      if (rootDirectory == null) {
        continue;
      }
      GradleContentRootImpl intellijContentRoot = new GradleContentRootImpl(rootDirectory.getAbsolutePath());
      populateContentRoot(intellijContentRoot, SourceType.SOURCE, gradleContentRoot.getSourceDirectories());
      populateContentRoot(intellijContentRoot, SourceType.TEST, gradleContentRoot.getTestDirectories());
      Set<File> excluded = gradleContentRoot.getExcludeDirectories();
      if (excluded != null) {
        for (File file : excluded) {
          intellijContentRoot.storePath(SourceType.EXCLUDED, file.getAbsolutePath());
        }
      }
      intellijModule.addContentRoot(intellijContentRoot);
    }
  }
  
  private static void populateContentRoot(@NotNull GradleContentRootImpl contentRoot, SourceType type,
                                          @Nullable Iterable<? extends IdeaSourceDirectory> dirs)
  {
    if (dirs == null) {
      return;
    }
    for (IdeaSourceDirectory dir : dirs) {
      contentRoot.storePath(type, dir.getDirectory().getAbsolutePath());
    }
  }
  
  private static void populateCompileOutputSettings(@Nullable IdeaCompilerOutput gradleSettings,
                                                    @NotNull GradleModuleImpl intellijModule)
  {
    if (gradleSettings == null) {
      return;
    }
    intellijModule.setInheritProjectCompileOutputPath(gradleSettings.getInheritOutputDirs());

    File sourceCompileOutputPath = gradleSettings.getOutputDir();
    if (sourceCompileOutputPath != null) {
      intellijModule.setCompileOutputPath(SourceType.SOURCE, sourceCompileOutputPath.getAbsolutePath());
    }

    File testCompileOutputPath = gradleSettings.getTestOutputDir();
    if (testCompileOutputPath != null) {
      intellijModule.setCompileOutputPath(SourceType.TEST, testCompileOutputPath.getAbsolutePath());
    }
  }

  private static void populateDependencies(@NotNull IdeaModule gradleModule, @NotNull GradleModuleImpl intellijModule, 
                                           @NotNull GradleProject intellijProject)
  {
    DomainObjectSet<? extends IdeaDependency> dependencies = gradleModule.getDependencies();
    if (dependencies == null) {
      return;
    }
    for (IdeaDependency dependency : dependencies) {
      if (dependency == null) {
        continue;
      }
      AbstractGradleDependency intellijDependency = null;
      if (dependency instanceof IdeaModuleDependency) {
        intellijDependency = buildDependency((IdeaModuleDependency)dependency, intellijProject);
      }
      else if (dependency instanceof IdeaSingleEntryLibraryDependency) {
        GradleLibraryDependency createdDependency = getCreatedDependency((IdeaSingleEntryLibraryDependency)dependency, intellijProject);
        if (createdDependency != null) {
          intellijModule.addDependency(createdDependency);
          return;
        } 
        intellijDependency = buildDependency((IdeaSingleEntryLibraryDependency)dependency);
      }

      if (intellijDependency == null) {
        continue;
      }
      
      intellijDependency.setExported(dependency.getExported());
      DependencyScope scope = parseScope(dependency.getScope());
      if (scope != null) {
        intellijDependency.setScope(scope);
      }
      intellijModule.addDependency(intellijDependency);
    }
  }

  @Nullable
  private static GradleLibraryDependency getCreatedDependency(final @NotNull IdeaSingleEntryLibraryDependency gradleDependency,
                                                              @NotNull GradleProject intellijProject)
  {
    final Ref<GradleLibraryDependency> result = new Ref<GradleLibraryDependency>();
    GradleEntityVisitor visitor = new GradleEntityVisitorAdapter() {
      @Override
      public void visit(@NotNull GradleLibraryDependency dependency) {
        String path = dependency.getPath(LibraryPathType.BINARY);
        if (path != null && gradleDependency.getFile().equals(new File(path))) {
          result.set(dependency);
        }
      }
    };
    for (GradleModule intellijModule : intellijProject.getModules()) {
      for (GradleDependency intellijDependency : intellijModule.getDependencies()) {
        intellijDependency.invite(visitor);
        if (result.get() != null) {
          return result.get();
        }
      }
    }
    return null;
  }
  
  @NotNull
  private static AbstractGradleDependency buildDependency(@NotNull IdeaModuleDependency dependency, @NotNull GradleProject intellijProject)
    throws IllegalStateException
  {
    IdeaModule module = dependency.getDependencyModule();
    if (module == null) {
      throw new IllegalStateException(
        String.format("Can't parse gradle module dependency '%s'. Reason: referenced module is null", dependency)
      );
    }

    String moduleName = module.getName();
    if (moduleName == null) {
      throw new IllegalStateException(String.format(
        "Can't parse gradle module dependency '%s'. Reason: referenced module name is undefined (module: '%s') ", dependency, module
      ));
    }
    
    Set<String> registeredModuleNames = new HashSet<String>();
    for (GradleModule gradleModule : intellijProject.getModules()) {
      registeredModuleNames.add(gradleModule.getName());
      if (gradleModule.getName().equals(moduleName)) {
        return new GradleModuleDependencyImpl(gradleModule);
      }
    }
    throw new IllegalStateException(String.format(
      "Can't parse gradle module dependency '%s'. Reason: no module with such name (%s) is found. Registered modules: %s",
      dependency, moduleName, registeredModuleNames
    ));
  }

  @NotNull
  private static AbstractGradleDependency buildDependency(@NotNull IdeaSingleEntryLibraryDependency dependency)
    throws IllegalStateException
  {
    File binaryPath = dependency.getFile();
    if (binaryPath == null) {
      throw new IllegalStateException(String.format(
        "Can't parse external library dependency '%s'. Reason: it doesn't specify path to the binaries", dependency
      ));
    }
    
    // TODO den use library name from gradle api when it's ready
    GradleLibraryDependencyImpl result = new GradleLibraryDependencyImpl(FileUtil.getNameWithoutExtension(binaryPath));
    result.addPath(LibraryPathType.BINARY, binaryPath.getAbsolutePath());

    File sourcePath = dependency.getSource();
    if (sourcePath != null) {
      result.addPath(LibraryPathType.SOURCE, sourcePath.getAbsolutePath());
    }

    File javadocPath = dependency.getJavadoc();
    if (javadocPath != null) {
      result.addPath(LibraryPathType.JAVADOC, javadocPath.getAbsolutePath());
    }
    return result;
  }

  @Nullable
  private static DependencyScope parseScope(@Nullable IdeaDependencyScope scope) {
    if (scope == null) {
      return null;
    }
    String scopeAsString = scope.getScope();
    if (scopeAsString == null) {
      return null;
    }
    for (DependencyScope dependencyScope : DependencyScope.values()) {
      if (scopeAsString.equalsIgnoreCase(dependencyScope.toString())) {
        return dependencyScope;
      }
    }
    return null;
  }
  
  /**
   * Allows to retrieve gradle api connection to use for the given project.
   * 
   * @param projectPath     target project path
   * @return                connection to use
   * @throws IllegalStateException    if it's not possible to create the connection
   */
  @NotNull
  private ProjectConnection getConnection(@NotNull String projectPath) throws IllegalStateException {
    File projectFile = new File(projectPath);
    if (!projectFile.isFile()) {
      throw new IllegalArgumentException(GradleBundle.message("gradle.import.text.error.invalid.path", projectPath));
    }
    File projectDir = projectFile.getParentFile();
    GradleConnector connector = GradleConnector.newConnector();
    RemoteGradleProcessSettings settings = mySettings.get();
    if (settings != null) {
      connector.useInstallation(new File(settings.getGradleHome()));
    } 
    connector.forProjectDirectory(projectDir);
    ProjectConnection connection = connector.connect();
    if (connection == null) {
      throw new IllegalStateException(String.format(
        "Can't create connection to the target project via gradle tooling api. Project path: '%s'", projectPath
      ));
    }
    myConnections.add(connection);
    return connection;
  }
  
  @SuppressWarnings("NonSynchronizedMethodOverridesSynchronizedMethod")
  @Override
  public void unreferenced() {
    releaseConnectionIfPossible();
    super.unreferenced();
  }

  private void releaseConnectionIfPossible() {
    while (!myConnections.isEmpty()) {
      try {
        ProjectConnection connection = myConnections.poll(1, TimeUnit.SECONDS);
        connection.close();
      }
      catch (InterruptedException e) {
        GradleLog.LOG.warn("Detected unexpected thread interruption on releasing gradle connections", e);
        Thread.currentThread().interrupt();
      }
      catch (Throwable e) {
        GradleLog.LOG.warn("Got unexpected exception on closing project connection created by the gradle tooling api", e);
      }
    }
  }

  @Override
  public void setSettings(@NotNull RemoteGradleProcessSettings settings) {
    mySettings.set(settings); 
  }
}
