// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins;

import com.intellij.AbstractBundle;
import com.intellij.CommonBundle;
import com.intellij.diagnostic.PluginException;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentConfig;
import com.intellij.openapi.components.OldComponentConfig;
import com.intellij.openapi.components.ServiceDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.ExtensionPoint;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.extensions.ExtensionsArea;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.extensions.impl.ExtensionPointImpl;
import com.intellij.openapi.extensions.impl.ExtensionsAreaImpl;
import com.intellij.openapi.util.*;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.SmartList;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashSetInterner;
import com.intellij.util.containers.Interner;
import com.intellij.util.containers.MultiMap;
import com.intellij.util.messages.ListenerDescriptor;
import com.intellij.util.xmlb.BeanBinding;
import com.intellij.util.xmlb.JDOMXIncluder;
import com.intellij.util.xmlb.XmlSerializer;
import org.jdom.Content;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.picocontainer.MutablePicoContainer;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class IdeaPluginDescriptorImpl implements IdeaPluginDescriptor {
  public static final IdeaPluginDescriptorImpl[] EMPTY_ARRAY = new IdeaPluginDescriptorImpl[0];

  private static final Logger LOG = Logger.getInstance("#com.intellij.ide.plugins.PluginDescriptor");

  private static final String APPLICATION_SERVICE = "com.intellij.applicationService";
  private static final String PROJECT_SERVICE = "com.intellij.projectService";
  private static final String MODULE_SERVICE = "com.intellij.moduleService";

  static final List<String> SERVICE_QUALIFIED_ELEMENT_NAMES = Arrays.asList(APPLICATION_SERVICE, PROJECT_SERVICE, MODULE_SERVICE);

  private final File myPath;
  private final boolean myBundled;
  private String myName;
  private PluginId myId;
  private final NullableLazyValue<String> myDescription = NullableLazyValue.createValue(() -> computeDescription());
  private @Nullable String myProductCode;
  private @Nullable Date myReleaseDate;
  private int myReleaseVersion;
  private String myResourceBundleBaseName;
  private String myChangeNotes;
  private String myVersion;
  private String myVendor;
  private String myVendorEmail;
  private String myVendorUrl;
  private String myVendorLogoPath;
  private String myCategory;
  private String myUrl;
  private PluginId[] myDependencies = PluginId.EMPTY_ARRAY;
  private PluginId[] myOptionalDependencies = PluginId.EMPTY_ARRAY;
  private Map<PluginId, List<String>> myOptionalConfigs;
  private Map<PluginId, List<IdeaPluginDescriptorImpl>> myOptionalDescriptors;
  private @Nullable List<Element> myActionElements;
  // extension point name -> list of extension elements
  private @Nullable MultiMap<String, Element> myExtensions;

  private final ContainerDescriptor myAppContainerDescriptor = new ContainerDescriptor();
  private final ContainerDescriptor myProjectContainerDescriptor = new ContainerDescriptor();
  private final ContainerDescriptor myModuleContainerDescriptor = new ContainerDescriptor();

  private List<String> myModules;
  private ClassLoader myLoader;
  private String myDescriptionChildText;
  private boolean myUseIdeaClassLoader;
  private boolean myUseCoreClassLoader;
  private boolean myAllowBundledUpdate;
  private boolean myImplementationDetail;
  private String mySinceBuild;
  private String myUntilBuild;

  private boolean myEnabled = true;
  private boolean myDeleted;
  private Boolean mySkipped;

  public IdeaPluginDescriptorImpl(@NotNull File pluginPath, boolean bundled) {
    myPath = pluginPath;
    myBundled = bundled;
  }

  @Override
  public File getPath() {
    return myPath;
  }

  public void readExternal(@NotNull Element element,
                           @NotNull URL url,
                           @NotNull JDOMXIncluder.PathResolver pathResolver,
                           @Nullable Interner<String> stringInterner) throws InvalidDataException, MalformedURLException {
    Application app = ApplicationManager.getApplication();
    readExternal(element, url, app != null && app.isUnitTestMode(), pathResolver, stringInterner);
  }

  public void loadFromFile(@NotNull File file, @Nullable SafeJdomFactory factory, boolean ignoreMissingInclude) throws IOException, JDOMException {
    readExternal(JDOMUtil.load(file, factory), file.toURI().toURL(), ignoreMissingInclude,
                 JDOMXIncluder.DEFAULT_PATH_RESOLVER, factory == null ? null : factory.stringInterner());
  }

  private void readExternal(@NotNull Element element,
                            @NotNull URL url,
                            boolean ignoreMissingInclude,
                            @NotNull JDOMXIncluder.PathResolver pathResolver,
                            @Nullable Interner<String> stringInterner) throws InvalidDataException, MalformedURLException {
    // root element always `!isIncludeElement` and it means that result always is a singleton list
    // (also, plugin xml describes one plugin, this descriptor is not able to represent several plugins)
    if (JDOMUtil.isEmpty(element)) {
      return;
    }

    String pluginId = element.getChildTextTrim("id");
    if (pluginId == null) pluginId = element.getChildTextTrim("name");
    if (pluginId == null || !PluginManagerCore.disabledPlugins().contains(pluginId)) {
      JDOMXIncluder.resolveNonXIncludeElement(element, url, ignoreMissingInclude, pathResolver);
    }
    else if (LOG.isDebugEnabled()) {
      LOG.debug("Skipping resolving of " + pluginId + " from " + url);
    }
    readExternal(element, stringInterner);
  }

  private void readExternal(@NotNull Element element, @Nullable Interner<String> stringInterner) {
    OptimizedPluginBean pluginBean = XmlSerializer.deserialize(element, OptimizedPluginBean.class);
    myUrl = pluginBean.url;

    String idString = StringUtil.nullize(pluginBean.id, true);
    String nameString = StringUtil.nullize(pluginBean.name, true);
    myId = idString != null ? PluginId.getId(idString) : nameString != null ? PluginId.getId(nameString) : null;
    myName = ObjectUtils.chooseNotNull(nameString, idString);

    ProductDescriptor pd = pluginBean.productDescriptor;
    myProductCode = pd != null? pd.code : null;
    myReleaseDate = parseReleaseDate(pluginBean);
    myReleaseVersion = pd != null? pd.releaseVersion : 0;

    String internalVersionString = pluginBean.formatVersion;
    if (internalVersionString != null) {
      try {
        Integer.parseInt(internalVersionString);
      }
      catch (NumberFormatException e) {
        LOG.error(new PluginException("Invalid value in plugin.xml format version: '" + internalVersionString + "'", e, myId));
      }
    }
    myUseIdeaClassLoader = pluginBean.useIdeaClassLoader;
    myAllowBundledUpdate = pluginBean.allowBundledUpdate;
    myImplementationDetail = pluginBean.implementationDetail;
    if (pluginBean.ideaVersion != null) {
      mySinceBuild = pluginBean.ideaVersion.sinceBuild;
      myUntilBuild = convertExplicitBigNumberInUntilBuildToStar(pluginBean.ideaVersion.untilBuild);
    }

    myResourceBundleBaseName = pluginBean.resourceBundle;

    myDescriptionChildText = pluginBean.description;
    myChangeNotes = pluginBean.changeNotes;
    myVersion = pluginBean.pluginVersion;
    if (myVersion == null) {
      myVersion = PluginManagerCore.getBuildNumber().asStringWithoutProductCode();
    }

    myCategory = pluginBean.category;

    if (pluginBean.vendor != null) {
      myVendor = pluginBean.vendor.name;
      myVendorEmail = pluginBean.vendor.email;
      myVendorUrl = pluginBean.vendor.url;
      myVendorLogoPath = pluginBean.vendor.logo;
    }

    // preserve items order as specified in xml (filterBadPlugins will not fail if module comes first)
    Set<PluginId> dependentPlugins = new LinkedHashSet<>();
    Set<PluginId> nonOptionalDependentPlugins = new LinkedHashSet<>();
    if (pluginBean.dependencies != null) {
      myOptionalConfigs = new LinkedHashMap<>();
      for (PluginDependency dependency : pluginBean.dependencies) {
        String text = dependency.pluginId;
        if (!StringUtil.isEmptyOrSpaces(text)) {
          PluginId id = PluginId.getId(text);
          dependentPlugins.add(id);
          if (dependency.optional) {
            if (!StringUtil.isEmptyOrSpaces(dependency.configFile)) {
              myOptionalConfigs.computeIfAbsent(id, it -> new SmartList<>()).add(dependency.configFile);
            }
          }
          else {
            nonOptionalDependentPlugins.add(id);
          }
        }
      }
    }

    myDependencies = dependentPlugins.isEmpty() ? PluginId.EMPTY_ARRAY : dependentPlugins.toArray(PluginId.EMPTY_ARRAY);
    if (nonOptionalDependentPlugins.size() == dependentPlugins.size()) {
      myOptionalDependencies = PluginId.EMPTY_ARRAY;
    }
    else {
      myOptionalDependencies = ContainerUtil.filter(dependentPlugins, id -> !nonOptionalDependentPlugins.contains(id)).toArray(PluginId.EMPTY_ARRAY);
    }

    // we cannot use our new kotlin-aware XmlSerializer, so, will be used different bean cache,
    // but it is not a problem because in any case new XmlSerializer is not used for our core classes (plugin bean, component config and so on).
    Ref<BeanBinding> oldComponentConfigBeanBinding = new Ref<>();

    // only for CoreApplicationEnvironment
    if (stringInterner == null) {
      stringInterner = new HashSetInterner<>(SERVICE_QUALIFIED_ELEMENT_NAMES);
    }

    MultiMap<String, Element> extensions = myExtensions;
    for (Content content : element.getContent()) {
      if (!(content instanceof Element)) {
        continue;
      }

      Element child = (Element)content;
      switch (child.getName()) {
        case "extensions":
          String ns = child.getAttributeValue("defaultExtensionNs");
          for (Element extensionElement : child.getChildren()) {
            String os = extensionElement.getAttributeValue("os");
            if (os != null) {
              extensionElement.removeAttribute("os");
              if (!isComponentSuitableForOs(os)) {
                continue;
              }
            }

            String qualifiedExtensionPointName = stringInterner.intern(ExtensionsAreaImpl.extractPointName(extensionElement, ns));
            ContainerDescriptor containerDescriptor;
            if (qualifiedExtensionPointName.equals(APPLICATION_SERVICE)) {
              containerDescriptor = myAppContainerDescriptor;
            }
            else if (qualifiedExtensionPointName.equals(PROJECT_SERVICE)) {
              containerDescriptor = myProjectContainerDescriptor;
            }
            else if (qualifiedExtensionPointName.equals(MODULE_SERVICE)) {
              containerDescriptor = myModuleContainerDescriptor;
            }
            else {
              if (extensions == null) {
                extensions = MultiMap.createSmart();
                myExtensions = extensions;
              }
              extensions.putValue(qualifiedExtensionPointName, extensionElement);
              continue;
            }

            containerDescriptor.addService(readServiceDescriptor(extensionElement));
          }
          break;

        case "extensionPoints":
          for (Element extensionPoint : child.getChildren()) {
            String area = extensionPoint.getAttributeValue(ExtensionsAreaImpl.ATTRIBUTE_AREA);
            ContainerDescriptor containerDescriptor = getContainerDescriptorByExtensionArea(area);
            if (containerDescriptor == null) {
              LOG.error("Unknown area: " + area);
              continue;
            }

            List<Element> result = containerDescriptor.extensionsPoints;
            if (result == null) {
              result = new ArrayList<>();
              containerDescriptor.extensionsPoints = result;
            }
            result.add(extensionPoint);
          }
          break;

        case "actions":
          if (myActionElements == null) {
            myActionElements = new ArrayList<>(child.getChildren());
          }
          else {
            myActionElements.addAll(child.getChildren());
          }
          break;

        case "module":
          String moduleName = child.getAttributeValue("value");
          if (moduleName != null) {
            if (myModules == null) {
              myModules = new SmartList<>();
            }
            myModules.add(moduleName);
          }
          break;

        case OptimizedPluginBean.APPLICATION_COMPONENTS:
          // because of x-pointer, maybe several application-components tag in document
          readComponents(child, oldComponentConfigBeanBinding, myAppContainerDescriptor);
          break;

        case OptimizedPluginBean.PROJECT_COMPONENTS:
          readComponents(child, oldComponentConfigBeanBinding, myProjectContainerDescriptor);
          break;

        case OptimizedPluginBean.MODULE_COMPONENTS:
          readComponents(child, oldComponentConfigBeanBinding, myModuleContainerDescriptor);
          break;

        case "applicationListeners":
          readListeners(child, myAppContainerDescriptor);
          break;
      }

      child.getContent().clear();
    }
  }

  private void readListeners(@NotNull Element list, @NotNull ContainerDescriptor containerDescriptor) {
    List<Content> content = list.getContent();
    List<ListenerDescriptor> result = containerDescriptor.listeners;
    if (result == null) {
      result = new ArrayList<>(content.size());
      containerDescriptor.listeners = result;
    }
    else {
      ((ArrayList<ListenerDescriptor>)result).ensureCapacity(result.size() + content.size());
    }

    for (Content item : content) {
      if (!(item instanceof Element)) {
        continue;
      }

      Element child = (Element)item;
      String listenerClassName = child.getAttributeValue("class");
      String topicClassName = child.getAttributeValue("topic");
      if (listenerClassName == null || topicClassName == null) {
        LOG.error("Listener descriptor is not correct: " + JDOMUtil.writeElement(child));
      }
      else {
        result.add(new ListenerDescriptor(listenerClassName, topicClassName,
                                          getBoolean("activeInTestMode", child), getBoolean("activeInHeadlessMode", child), this));
      }
    }
  }

  private static boolean getBoolean(@NotNull String name, @NotNull Element child) {
    String value = child.getAttributeValue(name);
    return value == null || Boolean.parseBoolean(value);
  }

  @NotNull
  private static ServiceDescriptor readServiceDescriptor(@NotNull Element element) {
    ServiceDescriptor descriptor = new ServiceDescriptor();
    descriptor.serviceInterface = element.getAttributeValue("serviceInterface");
    descriptor.serviceImplementation = element.getAttributeValue("serviceImplementation");
    descriptor.testServiceImplementation = element.getAttributeValue("testServiceImplementation");
    descriptor.configurationSchemaKey = element.getAttributeValue("configurationSchemaKey");
    descriptor.overrides = Boolean.parseBoolean(element.getAttributeValue("overrides"));
    return descriptor;
  }

  private static void readComponents(@NotNull Element parent, @NotNull Ref<BeanBinding> oldComponentConfigBean, @NotNull ContainerDescriptor containerDescriptor) {
    List<Content> content = parent.getContent();
    int contentSize = content.size();
    if (contentSize == 0) {
      return;
    }

    List<ComponentConfig> result = containerDescriptor.getComponentListToAdd(contentSize);
    for (Content child : content) {
      if (!(child instanceof Element)) {
        continue;
      }

      Element componentElement = (Element)child;
      if (!componentElement.getName().equals("component")) {
        continue;
      }

      OldComponentConfig componentConfig = new OldComponentConfig();

      BeanBinding beanBinding = oldComponentConfigBean.get();
      if (beanBinding == null) {
        beanBinding = XmlSerializer.getBeanBinding(componentConfig);
        oldComponentConfigBean.set(beanBinding);
      }

      beanBinding.deserializeInto(componentConfig, componentElement);
      Map<String, String> options = componentConfig.options;
      if (options != null && !isComponentSuitableForOs(options.get("os"))) {
        continue;
      }

      result.add(componentConfig);
    }
  }

  @Nullable
  private static Date parseReleaseDate(@NotNull OptimizedPluginBean bean) {
    final ProductDescriptor pd = bean.productDescriptor;
    final String dateStr = pd != null? pd.releaseDate : null;
    if (dateStr != null) {
      try {
        return new SimpleDateFormat("yyyyMMdd", Locale.US).parse(dateStr);
      }
      catch (ParseException e) {
        LOG.info("Error parse release date from plugin descriptor for plugin " + bean.name + " {" + bean.id + "}: " + e.getMessage());
      }
    }
    return null;
  }

  public static final Pattern EXPLICIT_BIG_NUMBER_PATTERN = Pattern.compile("(.*)\\.(9{4,}+|10{4,}+)");

  /**
   * Convert build number like '146.9999' to '146.*' (like plugin repository does) to ensure that plugins which have such values in
   * 'until-build' attribute will be compatible with 146.SNAPSHOT build.
   */
  public static String convertExplicitBigNumberInUntilBuildToStar(@Nullable String build) {
    if (build == null) return null;
    Matcher matcher = EXPLICIT_BIG_NUMBER_PATTERN.matcher(build);
    if (matcher.matches()) {
      return matcher.group(1) + ".*";
    }
    return build;
  }

  public void registerExtensionPoints(@NotNull ExtensionsArea area) {
    ContainerDescriptor containerDescriptor = getContainerDescriptorByExtensionArea(area.getAreaClass());
    if (containerDescriptor == null) {
      throw new IllegalStateException("Unknown area: " + area);
    }

    List<Element> extensionsPoints = containerDescriptor.extensionsPoints;
    if (extensionsPoints != null) {
      for (Element element : extensionsPoints) {
        area.registerExtensionPoint(this, element);
      }
    }
  }

  @Nullable
  private ContainerDescriptor getContainerDescriptorByExtensionArea(@Nullable String area) {
    if (area == null) {
      return myAppContainerDescriptor;
    }
    else if ("IDEA_PROJECT".equals(area)) {
      return myProjectContainerDescriptor;
    }
    else if ("IDEA_MODULE".equals(area)) {
      return myModuleContainerDescriptor;
    }
    else {
      return null;
    }
  }

  void registerExtensions(@NotNull ExtensionPointImpl<?>[] extensionPoints, @NotNull MutablePicoContainer picoContainer) {
    if (myExtensions == null) {
      return;
    }

    for (ExtensionPointImpl<?> extensionPoint : extensionPoints) {
      extensionPoint.createAndRegisterAdapters(myExtensions.get(extensionPoint.getName()), this, picoContainer);
    }
  }

  // made public for Upsource
  public void registerExtensions(@NotNull ExtensionsArea area, @NotNull ExtensionPoint<?> extensionPoint) {
    if (myExtensions == null) {
      return;
    }
    ((ExtensionPointImpl)extensionPoint).createAndRegisterAdapters(myExtensions.get(extensionPoint.getName()), this, area.getPicoContainer());
  }

  @Override
  public String getDescription() {
    return myDescription.getValue();
  }

  @Override
  public String getChangeNotes() {
    return myChangeNotes;
  }

  @Override
  public String getName() {
    return myName;
  }

  @Nullable
  @Override
  public String getProductCode() {
    return myProductCode;
  }

  @Nullable
  @Override
  public Date getReleaseDate() {
    return myReleaseDate;
  }

  @Override
  public int getReleaseVersion() {
    return myReleaseVersion;
  }

  @Override
  @NotNull
  public PluginId[] getDependentPluginIds() {
    return myDependencies;
  }

  @Override
  @NotNull
  public PluginId[] getOptionalDependentPluginIds() {
    return myOptionalDependencies;
  }

  @Override
  public String getVendor() {
    return myVendor;
  }

  @Override
  public String getVersion() {
    return myVersion;
  }

  @Override
  public String getResourceBundleBaseName() {
    return myResourceBundleBaseName;
  }

  @Override
  public String getCategory() {
    return myCategory;
  }

  /*
     This setter was explicitly defined to be able to set a category for a
     descriptor outside its loading from the xml file.
     Problem was that most commonly plugin authors do not publish the plugin's
     category in its .xml file so to be consistent in plugins representation
     (e.g. in the Plugins form) we have to set this value outside.
  */
  public void setCategory(String category) {
    myCategory = category;
  }

  @SuppressWarnings("UnusedDeclaration") // Used in Upsource
  @Nullable
  public MultiMap<String, Element> getExtensions() {
    if (myExtensions == null) return null;
    MultiMap<String, Element> result = MultiMap.create();
    result.putAllValues(myExtensions);
    return result;
  }

  @SuppressWarnings("HardCodedStringLiteral")
  @NotNull
  public List<File> getClassPath() {
    if (myPath.isDirectory()) {
      final List<File> result = new ArrayList<>();
      final File classesDir = new File(myPath, "classes");

      if (classesDir.exists()) {
        result.add(classesDir);
      }

      final File[] files = new File(myPath, "lib").listFiles();
      if (files != null && files.length > 0) {
        for (final File f : files) {
          if (f.isFile()) {
            final String name = f.getName();
            if (StringUtil.endsWithIgnoreCase(name, ".jar") || StringUtil.endsWithIgnoreCase(name, ".zip")) {
              result.add(f);
            }
          }
          else {
            result.add(f);
          }
        }
      }

      return result;
    }
    else {
      return Collections.singletonList(myPath);
    }
  }

  @Override
  @Nullable
  public List<Element> getAndClearActionDescriptionElements() {
    List<Element> result = myActionElements;
    myActionElements = null;
    return result;
  }

  @Override
  @NotNull
  public List<ComponentConfig> getAppComponents() {
    return ContainerUtil.notNullize(myAppContainerDescriptor.components);
  }

  @Override
  @NotNull
  public List<ComponentConfig> getProjectComponents() {
    return ContainerUtil.notNullize(myProjectContainerDescriptor.components);
  }

  @Override
  @NotNull
  public List<ComponentConfig> getModuleComponents() {
    return ContainerUtil.notNullize(myModuleContainerDescriptor.components);
  }

  @NotNull
  public List<ServiceDescriptor> getAppServices() {
    return ContainerUtil.notNullize(myAppContainerDescriptor.services);
  }

  @NotNull
  public List<ListenerDescriptor> getListeners() {
    return ContainerUtil.notNullize(myAppContainerDescriptor.listeners);
  }

  @NotNull
  public List<ServiceDescriptor> getProjectServices() {
    return ContainerUtil.notNullize(myProjectContainerDescriptor.services);
  }

  @NotNull
  public List<ServiceDescriptor> getModuleServices() {
    return ContainerUtil.notNullize(myModuleContainerDescriptor.services);
  }

  @Override
  public String getVendorEmail() {
    return myVendorEmail;
  }

  @Override
  public String getVendorUrl() {
    return myVendorUrl;
  }

  @Override
  public String getUrl() {
    return myUrl;
  }

  public void setUrl(String val) {
    myUrl = val;
  }

  public boolean isDeleted() {
    return myDeleted;
  }

  public void setDeleted(boolean deleted) {
    myDeleted = deleted;
  }

  public void setLoader(ClassLoader loader) {
    myLoader = loader;
  }

  @Override
  public PluginId getPluginId() {
    return myId;
  }

  /** @deprecated doesn't make sense for installed plugins; use PluginNode#getDownloads (to be removed in IDEA 2019) */
  @ApiStatus.ScheduledForRemoval(inVersion = "2019")
  @Override
  @Deprecated
  public String getDownloads() {
    return null;
  }

  @Override
  public ClassLoader getPluginClassLoader() {
    return myLoader != null ? myLoader : getClass().getClassLoader();
  }

  @Override
  public String getVendorLogoPath() {
    return myVendorLogoPath;
  }

  @Override
  public boolean getUseIdeaClassLoader() {
    return myUseIdeaClassLoader;
  }

  boolean isUseCoreClassLoader() {
    return myUseCoreClassLoader;
  }

  void setUseCoreClassLoader(@SuppressWarnings("SameParameterValue") boolean useCoreClassLoader) {
    myUseCoreClassLoader = useCoreClassLoader;
  }

  private String computeDescription() {
    ResourceBundle bundle = null;
    if (myResourceBundleBaseName != null) {
      try {
        bundle = AbstractBundle.getResourceBundle(myResourceBundleBaseName, getPluginClassLoader());
      }
      catch (MissingResourceException e) {
        LOG.info("Cannot find plugin " + myId + " resource-bundle: " + myResourceBundleBaseName);
      }
    }

    if (bundle == null) {
      return myDescriptionChildText;
    }

    return CommonBundle.messageOrDefault(bundle, "plugin." + myId + ".description", StringUtil.notNullize(myDescriptionChildText));
  }

  void insertDependency(@NotNull IdeaPluginDescriptor d) {
    PluginId[] deps = new PluginId[getDependentPluginIds().length + 1];
    deps[0] = d.getPluginId();
    System.arraycopy(myDependencies, 0, deps, 1, deps.length - 1);
    myDependencies = deps;
  }

  @Override
  public boolean isEnabled() {
    return myEnabled;
  }

  @Override
  public void setEnabled(final boolean enabled) {
    myEnabled = enabled;
  }

  @Override
  public String getSinceBuild() {
    return mySinceBuild;
  }

  @Override
  public String getUntilBuild() {
    return myUntilBuild;
  }

  Map<PluginId, List<String>> getOptionalConfigs() {
    return myOptionalConfigs;
  }

  @Nullable
  Map<PluginId, List<IdeaPluginDescriptorImpl>> getOptionalDescriptors() {
    return myOptionalDescriptors;
  }

  void setOptionalDescriptors(@Nullable Map<PluginId, List<IdeaPluginDescriptorImpl>> optionalDescriptors) {
    myOptionalDescriptors = optionalDescriptors;
  }

  void mergeOptionalConfig(@NotNull IdeaPluginDescriptorImpl descriptor) {
    if (myExtensions == null) {
      myExtensions = descriptor.myExtensions;
    }
    else if (descriptor.myExtensions != null) {
      myExtensions.putAllValues(descriptor.myExtensions);
    }

    if (myActionElements == null) {
      myActionElements = descriptor.myActionElements;
    }
    else if (descriptor.myActionElements != null) {
      myActionElements.addAll(descriptor.myActionElements);
    }

    myAppContainerDescriptor.merge(descriptor.myAppContainerDescriptor);
    myProjectContainerDescriptor.merge(descriptor.myProjectContainerDescriptor);
    myModuleContainerDescriptor.merge(descriptor.myModuleContainerDescriptor);
  }

  public Boolean getSkipped() {
    return mySkipped;
  }

  public void setSkipped(final Boolean skipped) {
    mySkipped = skipped;
  }

  @Override
  public boolean isBundled() {
    return myBundled;
  }

  @Override
  public boolean allowBundledUpdate() {
    return myAllowBundledUpdate;
  }

  @Override
  public boolean isImplementationDetail() {
    return myImplementationDetail;
  }

  @NotNull
  public List<String> getModules() {
    return ContainerUtil.notNullize(myModules);
  }

  @Override
  public boolean equals(Object o) {
    return this == o || o instanceof IdeaPluginDescriptorImpl && myId == ((IdeaPluginDescriptorImpl)o).myId;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(myId);
  }

  @Override
  public String toString() {
    return "PluginDescriptor(name=" + myName + ", classpath=" + myPath + ")";
  }

  @ApiStatus.Internal
  public void processExtensionPoints(@NotNull Consumer<Element> consumer) {
    if (myAppContainerDescriptor.extensionsPoints != null) {
      myAppContainerDescriptor.extensionsPoints.forEach(consumer);
    }
    if (myProjectContainerDescriptor.extensionsPoints != null) {
      myProjectContainerDescriptor.extensionsPoints.forEach(consumer);
    }
    if (myModuleContainerDescriptor.extensionsPoints != null) {
      myModuleContainerDescriptor.extensionsPoints.forEach(consumer);
    }
  }

  private static boolean isComponentSuitableForOs(@Nullable String os) {
    if (StringUtil.isEmpty(os)) {
      return true;
    }

    if (os.equals(Extensions.OS.mac.name())) {
      return SystemInfo.isMac;
    }
    else if (os.equals(Extensions.OS.linux.name())) {
      return SystemInfo.isLinux;
    }
    else if (os.equals(Extensions.OS.windows.name())) {
      return SystemInfo.isWindows;
    }
    else if (os.equals(Extensions.OS.unix.name())) {
      return SystemInfo.isUnix;
    }
    else if (os.equals(Extensions.OS.freebsd.name())) {
      return SystemInfo.isFreeBSD;
    }
    else {
      throw new IllegalArgumentException("Unknown OS '" + os + "'");
    }
  }
}