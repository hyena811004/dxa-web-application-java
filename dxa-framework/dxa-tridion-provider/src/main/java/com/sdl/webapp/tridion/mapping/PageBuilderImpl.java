package com.sdl.webapp.tridion.mapping;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.sdl.webapp.common.api.WebRequestContext;
import com.sdl.webapp.common.api.content.ContentProvider;
import com.sdl.webapp.common.api.content.ContentProviderException;
import com.sdl.webapp.common.api.content.RegionBuilder;
import com.sdl.webapp.common.api.content.RegionBuilderCallback;
import com.sdl.webapp.common.api.localization.Localization;
import com.sdl.webapp.common.api.mapping.semantic.SemanticMapper;
import com.sdl.webapp.common.api.mapping.semantic.SemanticMappingException;
import com.sdl.webapp.common.api.mapping.semantic.config.SemanticSchema;
import com.sdl.webapp.common.api.model.EntityModel;
import com.sdl.webapp.common.api.model.MvcData;
import com.sdl.webapp.common.api.model.PageModel;
import com.sdl.webapp.common.api.model.RegionModel;
import com.sdl.webapp.common.api.model.RegionModelSet;
import com.sdl.webapp.common.api.model.ViewModel;
import com.sdl.webapp.common.api.model.ViewModelRegistry;
import com.sdl.webapp.common.api.model.mvcdata.DefaultsMvcData;
import com.sdl.webapp.common.api.model.mvcdata.MvcDataCreator;
import com.sdl.webapp.common.api.model.page.DefaultPageModel;
import com.sdl.webapp.common.api.model.region.RegionModelImpl;
import com.sdl.webapp.common.api.model.region.RegionModelSetImpl;
import com.sdl.webapp.common.exceptions.DxaException;
import com.sdl.webapp.common.util.TcmUtils;
import com.sdl.webapp.tridion.SemanticFieldDataProviderImpl;
import com.sdl.webapp.tridion.fields.FieldConverterRegistry;
import com.sdl.webapp.util.dd4t.FieldUtils;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.dd4t.contentmodel.ComponentPresentation;
import org.dd4t.contentmodel.ComponentTemplate;
import org.dd4t.contentmodel.Field;
import org.dd4t.contentmodel.FieldSet;
import org.dd4t.contentmodel.FieldType;
import org.dd4t.contentmodel.Page;
import org.dd4t.contentmodel.PageTemplate;
import org.dd4t.contentmodel.Schema;
import org.dd4t.contentmodel.impl.BaseField;
import org.dd4t.core.exceptions.ItemNotFoundException;
import org.dd4t.core.exceptions.SerializationException;
import org.dd4t.core.factories.ComponentPresentationFactory;
import org.dd4t.core.resolvers.LinkResolver;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Strings.isNullOrEmpty;

@Component
public final class PageBuilderImpl implements PageBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(PageBuilderImpl.class);

    private static final String IMAGE_FIELD_NAME = "image";

    private static final String REGION_FOR_PAGE_TITLE_COMPONENT = "Main";

    private static final String STANDARD_METADATA_FIELD_NAME = "standardMeta";

    private static final String STANDARD_METADATA_TITLE_FIELD_NAME = "name";

    private static final String STANDARD_METADATA_DESCRIPTION_FIELD_NAME = "description";

    private static final String COMPONENT_PAGE_TITLE_FIELD_NAME = "headline";

    private static final String DEFAULT_AREA_NAME = "Core";

    private static final String PAGE_CONTROLLER_NAME = "Page";

    private static final String PAGE_ACTION_NAME = "Page";

    private static final String REGION_CONTROLLER_NAME = "Region";

    private static final String REGION_ACTION_NAME = "Region";

    private static final String DEFAULT_REGION_NAME = "Main";

    private static final Pattern REGION_VIEW_NAME_PATTERN = Pattern.compile(".*\\[(.*)\\]");

    @Autowired
    private ModelBuilderPipeline modelBuilderPipeline;

    @Autowired
    private LinkResolver linkResolver;

    @Autowired
    private WebRequestContext webRequestContext;

    @Autowired
    private ViewModelRegistry viewModelRegistry;

    @Autowired
    private FieldConverterRegistry fieldConverterRegistry;

    @Autowired
    private RegionBuilder regionBuilder;

    @Autowired
    private SemanticMapper semanticMapper;

    @Autowired
    private ComponentPresentationFactory dd4tComponentPresentationFactory;

    private static RegionModel getRegionFromIncludePage(PageModel page, String includeFileName) {
        try {
            MvcData regionMvcData = MvcDataCreator.creator()
                    .fromQualifiedName(page.getName())
                    .defaults(DefaultsMvcData.CORE_REGION)
                    .create();

            // ViewName is a RegionName for include regions
            RegionModelImpl region = new RegionModelImpl(regionMvcData.getViewName());
            region.setMvcData(regionMvcData);
            ImmutableMap.Builder<String, Object> xpmMetaDataBuilder = ImmutableMap.builder();

            xpmMetaDataBuilder.put(RegionModelImpl.INCLUDED_FROM_PAGE_ID_XPM_METADATA_KEY, page.getId());
            xpmMetaDataBuilder.put(RegionModelImpl.INCLUDED_FROM_PAGE_TITLE_XPM_METADATA_KEY, page.getTitle());
            xpmMetaDataBuilder.put(RegionModelImpl.INCLUDED_FROM_PAGE_FILE_NAME_XPM_METADATA_KEY, includeFileName);

            region.setRegions(new RegionModelSetImpl());
            region.setXpmMetadata(xpmMetaDataBuilder.build());
            return region;

        } catch (DxaException e) {
            LOG.error("Error creating new MvcData from includepage", e);
            //todo return something but not null
            return null;
        }
    }

    static RegionModelSet mergeAllTopLevelRegions(@NonNull RegionModelSet predefinedRegions, @NonNull RegionModelSet regions) {
        //todo dxa2 do not change incoming parameter, create a copy instead
        for (RegionModel model : regions) {
            RegionModel predefined = predefinedRegions.get(model.getName());

            if (predefined == null) {
                predefinedRegions.add(model);
                continue;
            }

            // Region already exists in page model, so MVC data should match
            assertRegionWithNotConflictingMvcData(model.getName(), predefined.getMvcData(), model.getMvcData());

            //merge entities in
            for (EntityModel entityModel : model.getEntities()) {
                predefined.addEntity(entityModel);
            }
        }

        return predefinedRegions;
    }

    private static void assertRegionWithNotConflictingMvcData(String name, MvcData first, MvcData second) {
        if (!Objects.equals(first, second)) {
            LOG.warn("Region '{}' is defined with conflicting MVC data: [{}] and [{}]. Using the former.", name, first, second);
        }
    }

    private static String extract(Map<String, Field> metaMap, String key) {
        return metaMap.get(key).getValues().get(0).toString();
    }

    private static String getRegionName(ComponentPresentation cp) {
        final Map<String, Field> templateMeta = cp.getComponentTemplate().getMetadata();
        if (templateMeta != null) {
            String regionName = FieldUtils.getStringValue(templateMeta, "regionName");
            if (isNullOrEmpty(regionName)) {
                //fallback if region name field is empty, use regionView name
                regionName = FieldUtils.getStringValue(templateMeta, "regionView");
                if (isNullOrEmpty(regionName)) {
                    regionName = DEFAULT_REGION_NAME;
                }
            }
            return regionName;
        }

        return null;
    }

    private static Map<String, Object> createXpmMetaData(org.dd4t.contentmodel.Page page, Localization localization) {
        final PageTemplate pageTemplate = page.getPageTemplate();

        ImmutableMap.Builder<String, Object> xpmMetaDataBuilder = ImmutableMap.builder();
        xpmMetaDataBuilder.put("PageID", page.getId());
        xpmMetaDataBuilder.put("PageModified", ISODateTimeFormat.dateHourMinuteSecond().print(page.getRevisionDate()));
        xpmMetaDataBuilder.put("PageTemplateID", pageTemplate.getId());
        xpmMetaDataBuilder.put("PageTemplateModified",
                ISODateTimeFormat.dateHourMinuteSecond().print(pageTemplate.getRevisionDate()));

        xpmMetaDataBuilder.put("CmsUrl", localization.getConfiguration("core.cmsurl"));

        return xpmMetaDataBuilder.build();
    }

    private static String[] getPageViewNameParts(PageTemplate pageTemplate) {
        String fullName = FieldUtils.getStringValue(pageTemplate.getMetadata(), "view");
        if (isNullOrEmpty(fullName)) {
            fullName = pageTemplate.getTitle().replaceAll(" ", "");
        }
        return splitName(fullName);
    }

    private static String[] getRegionViewNameParts(ComponentTemplate componentTemplate) {
        String fullName = FieldUtils.getStringValue(componentTemplate.getMetadata(), "regionView");
        if (isNullOrEmpty(fullName)) {
            final Matcher matcher = REGION_VIEW_NAME_PATTERN.matcher(componentTemplate.getTitle());
            fullName = matcher.matches() ? matcher.group(1) : DEFAULT_REGION_NAME;
        }
        return splitName(fullName);
    }

    private static String[] splitName(String name) {
        final String[] parts = name.split(":");
        return parts.length > 1 ? parts : new String[]{DEFAULT_AREA_NAME, name};
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PageModel createPage(org.dd4t.contentmodel.Page genericPage, PageModel originalPageModel, Localization localization, ContentProvider contentProvider)
            throws ContentProviderException {

        final PageModel page;
        try {
            page = createPageModel(genericPage, localization);
        } catch (DxaException e) {
            throw new ContentProviderException(e);
        }

        RegionModelSet predefinedRegions = createPredefinedRegions(genericPage.getPageTemplate());
        page.setRegions(predefinedRegions);

        RegionModelSet cpRegions = regionBuilder.buildRegions(page, genericPage.getComponentPresentations(), new DD4TRegionBuilderCallback(), localization);

        final RegionModelSet regions = mergeAllTopLevelRegions(predefinedRegions, cpRegions);

        String localizationPath = localization.getPath();
        if (!localizationPath.endsWith("/")) {
            localizationPath = localizationPath + '/';
        }

        // Get and add includes
        final String pageTypeId = genericPage.getPageTemplate().getId().split("-")[1];
        for (String include : localization.getIncludes(pageTypeId)) {
            final String includeUrl = localizationPath + include;
            PageModel includePageModel = contentProvider.getPageModel(includeUrl, localization);
            final RegionModel includePageRegion = getRegionFromIncludePage(includePageModel, include);

            RegionModel existingRegion;
            if (includePageRegion != null) {
                if (regions.containsName(includePageRegion.getName())) {
                    // Region with same name already exists; merge include Page Region.
                    existingRegion = regions.get(includePageRegion.getName());

                    existingRegion.getRegions().addAll(includePageModel.getRegions());

                    Map<String, Object> xpmMetadata = existingRegion.getXpmMetadata();
                    if (xpmMetadata != null) {
                        xpmMetadata.remove(RegionModelImpl.INCLUDED_FROM_PAGE_ID_XPM_METADATA_KEY);
                        xpmMetadata.remove(RegionModelImpl.INCLUDED_FROM_PAGE_TITLE_XPM_METADATA_KEY);
                        xpmMetadata.remove(RegionModelImpl.INCLUDED_FROM_PAGE_FILE_NAME_XPM_METADATA_KEY);
                    }

                    LOG.info("Merged Include Page [{}] into Region [{}]. " +
                                    "Note that merged Regions can't be edited properly in XPM (yet).",
                            includePageModel, existingRegion);
                } else {
                    includePageRegion.getRegions().addAll(includePageModel.getRegions());
                    regions.add(includePageRegion);
                }
            }
        }

        page.setRegions(regions);

        return page;
    }

    private PageModel createPageModel(org.dd4t.contentmodel.Page genericPage, Localization localization) throws DxaException, ContentProviderException {
        MvcData pageMvcData = createPageMvcData(genericPage.getPageTemplate());
        Class pageModelType = viewModelRegistry.getViewModelType(pageMvcData);

        Schema pageMetadataSchema = genericPage.getSchema();

        PageModel pageModel;
        if (pageModelType == DefaultPageModel.class) {
            // Standard Page Model
            pageModel = new DefaultPageModel();
        } else if (pageModelType != null && pageMetadataSchema == null) {
            // Custom Page Model but no Page metadata that can be mapped; simply create a Page Model instance of the right type.
            try {
                pageModel = (PageModel) pageModelType.newInstance();
            } catch (InstantiationException e) {
                throw new DxaException(String.format("Error instantiating new page of type %s", pageModelType), e);
            } catch (IllegalAccessException e) {
                throw new DxaException(String.format("Illegal access exception when instantiating new page of type %s", pageModelType), e);
            }
        } else if(pageMetadataSchema != null){
            // Custom Page Model and Page metadata is present; do full-blown model mapping.
            String[] schemaTcmUriParts = pageMetadataSchema.getId().split("-");

            final SemanticSchema semanticSchema = localization.getSemanticSchemas().get(Long.parseLong(schemaTcmUriParts[1]));

            final Class<? extends ViewModel> entityClass = viewModelRegistry.getMappedModelTypes(semanticSchema.getFullyQualifiedNames());
            pageModel = (PageModel) createViewModel(entityClass, semanticSchema, genericPage);
        } else {
            throw new DxaException(String.format("Cannot instantiate new page of template %s", genericPage.getPageTemplate().getTitle()));
        }

        pageModel.setId(String.valueOf(TcmUtils.getItemId(genericPage.getId())));

        // It's confusing, but what DD4T calls the "title" is what is called the "name" in the view model
        pageModel.setName(genericPage.getTitle());

        final Map<String, String> pageMeta = new HashMap<>();
        final String title = processPageMetadata(genericPage, pageMeta, localization);
        pageModel.setTitle(title);
        pageModel.setMeta(pageMeta);
        pageModel.setMvcData(pageMvcData);

        pageModel.setXpmMetadata(createXpmMetaData(genericPage, localization));
        pageModel.setMvcData(createPageMvcData(genericPage.getPageTemplate()));

        String htmlClasses = FieldUtils.getStringValue(genericPage.getPageTemplate().getMetadata(), "htmlClasses");
        if (!isNullOrEmpty(htmlClasses)) {
            pageModel.setHtmlClasses(htmlClasses.replaceAll("[^\\w\\- ]", ""));
        }

        return pageModel;
    }

    private ViewModel createViewModel(Class<? extends ViewModel> entityClass, SemanticSchema semanticSchema, Page page) throws ContentProviderException {

        final ViewModel entity;
        try {
            entity = semanticMapper.createEntity(entityClass, semanticSchema.getSemanticFields(),
                    new SemanticFieldDataProviderImpl(
                            new SemanticFieldDataProviderImpl.PageEntity(page), fieldConverterRegistry, this.modelBuilderPipeline));
        } catch (SemanticMappingException e) {
            throw new ContentProviderException(e);
        }
        return entity;
    }

    RegionModelSet createPredefinedRegions(PageTemplate pageTemplate) {

        RegionModelSet regions = new RegionModelSetImpl();

        final Map<String, Field> pageTemplateMeta = pageTemplate.getMetadata();

        // TODO: "region" instead of "regions"
        if (pageTemplateMeta == null || !pageTemplateMeta.containsKey("regions")) {
            LOG.debug("No Region metadata defined for Page Template '{}'.", pageTemplate.getId());
            return regions;
        }

        BaseField regionsMetaField = (BaseField) pageTemplateMeta.get("regions");
        if (regionsMetaField == null || regionsMetaField.getEmbeddedValues() == null) {
            LOG.error("Regions meta {} exist in a page template {}, but it is null!", regionsMetaField, pageTemplate);
            return regions;
        }

        for (FieldSet regionField : regionsMetaField.getEmbeddedValues()) {
            Map<String, Field> region = regionField.getContent();
            if (!region.containsKey("view")) {
                LOG.warn("Region metadata without 'view' field encountered in metadata of Page Template '{}'.", pageTemplate.getId());
                continue;
            }
            String viewName = FieldUtils.getStringValue(region, "view");

            String regionName = FieldUtils.getStringValue(region, "name");
            if (isNullOrEmpty(regionName)) {
                regionName = viewName;
            }

            MvcData mvcData = MvcDataCreator.creator()
                    .fromQualifiedName(viewName)
                    .defaults(DefaultsMvcData.CORE_REGION)
                    .builder()
                    .regionName(regionName).build();

            try {
                RegionModel regionModel = createRegionModel(mvcData);
                if (!regions.add(regionModel)) {
                    // Region already exists in page model, so MVC data should match
                    assertRegionWithNotConflictingMvcData(regionName, regions.get(regionName).getMvcData(), mvcData);
                }
            } catch (IllegalAccessException | InstantiationException | DxaException | InvocationTargetException | NoSuchMethodException e) {
                LOG.error("Error creating region for view '{}'.", viewName, e);
            }
        }

        return regions;
    }

    private RegionModel createRegionModel(MvcData regionMvcData)
            throws IllegalAccessException, InstantiationException, DxaException, NoSuchMethodException, InvocationTargetException {
        Class<? extends ViewModel> regionModelType = this.viewModelRegistry.getViewModelType(regionMvcData);

        if (regionModelType == null) {
            throw new DxaException("Have you configured your views/modules properly? Region model type not found for " + regionMvcData);
        }

        Constructor<? extends ViewModel> constructor = regionModelType.getDeclaredConstructor(MvcData.class);
        return (RegionModel) constructor.newInstance(regionMvcData);
    }

    private String processPageMetadata(org.dd4t.contentmodel.Page page, Map<String, String> pageMeta, Localization localization) {
        // Process page metadata fields
        if (page.getMetadata() != null) {
            for (Field field : page.getMetadata().values()) {
                pageMeta.putAll(processMetadataField(field));
            }
        }

        String description = pageMeta.get("description");
        String title = pageMeta.get("title");
        String image = pageMeta.get(IMAGE_FIELD_NAME);

        if (isNullOrEmpty(title) || isNullOrEmpty(description)) {
            for (ComponentPresentation cp : page.getComponentPresentations()) {
                if (Objects.equals(REGION_FOR_PAGE_TITLE_COMPONENT, getRegionName(cp))) {
                    final org.dd4t.contentmodel.Component component = cp.getComponent();

                    final Map<String, Field> metadata = component.getMetadata();
                    BaseField standardMetaField = (BaseField) metadata.get(STANDARD_METADATA_FIELD_NAME);
                    if (standardMetaField != null && !standardMetaField.getEmbeddedValues().isEmpty()) {
                        final Map<String, Field> standardMeta = standardMetaField.getEmbeddedValues().get(0).getContent();
                        if (isNullOrEmpty(title) && standardMeta.containsKey(STANDARD_METADATA_TITLE_FIELD_NAME)) {
                            title = extract(standardMeta, STANDARD_METADATA_TITLE_FIELD_NAME);
                        }
                        if (isNullOrEmpty(description) && standardMeta.containsKey(STANDARD_METADATA_DESCRIPTION_FIELD_NAME)) {
                            description = extract(standardMeta, STANDARD_METADATA_DESCRIPTION_FIELD_NAME);
                        }
                    }

                    final Map<String, Field> content = component.getContent();
                    if (isNullOrEmpty(title) && content.containsKey(COMPONENT_PAGE_TITLE_FIELD_NAME)) {
                        title = extract(content, COMPONENT_PAGE_TITLE_FIELD_NAME);
                    }

                    if (isNullOrEmpty(image) && content.containsKey(IMAGE_FIELD_NAME)) {
                        image = ((BaseField) content.get(IMAGE_FIELD_NAME))
                                .getLinkedComponentValues().get(0).getMultimedia().getUrl();
                    }
                    break;
                }
            }
        }

        // Use page title if no title found
        if (isNullOrEmpty(title)) {
            title = page.getTitle();
            if (title.equalsIgnoreCase("index") || title.equalsIgnoreCase("default")) {
                // Use default page title from configuration if nothing better was found
                title = localization.getResource("core.defaultPageTitle");
            }
        }

        title = title.replaceFirst("\\d{3}\\s", "");

        pageMeta.put("twitter:card", "summary");
        pageMeta.put("og:title", title);
        pageMeta.put("og:url", webRequestContext.getFullUrl());
        pageMeta.put("og:type", "article");
        pageMeta.put("og:locale", localization.getCulture());

        if (!isNullOrEmpty(description)) {
            pageMeta.put("og:description", description);
        }

        if (!isNullOrEmpty(image)) {
            pageMeta.put("og:image", webRequestContext.getBaseUrl() + webRequestContext.getContextPath() + image);
        }

        if (!pageMeta.containsKey("description")) {
            pageMeta.put("description", !isNullOrEmpty(description) ? description : title);
        }

        String titlePostfix = localization.getResource("core.pageTitleSeparator") + localization.getResource("core.pageTitlePostfix");

        return title + titlePostfix;
    }

    private Map<String, String> processMetadataField(final Field field) {
        Map<String, String> result = new HashMap<>();

        // If it's an embedded field, then process the subfields
        if (field.getFieldType() == FieldType.EMBEDDED) {
            final List<FieldSet> embeddedValues = ((BaseField) field).getEmbeddedValues();
            if (embeddedValues != null && !embeddedValues.isEmpty()) {
                for (Field subField : embeddedValues.get(0).getContent().values()) {
                    result.putAll(processMetadataField(subField));
                }
            }
        } else {
            final String fieldName = field.getName();

            String value;
            switch (fieldName) {
                case "internalLink":
                    final String componentId = ((BaseField) field).getTextValues().get(0);
                    try {
                        value = linkResolver.resolve(componentId);
                    } catch (SerializationException | ItemNotFoundException e) {
                        LOG.warn("Error while resolving link: {}", componentId);
                        value = componentId;
                    }
                    break;
                case IMAGE_FIELD_NAME:
                    value = ((BaseField) field).getLinkedComponentValues().get(0).getMultimedia().getUrl();
                    break;
                default:
                    value = Joiner.on(',').join(field.getValues());
                    break;
            }

            if (!(StringUtils.isEmpty(value) || result.containsKey(fieldName))) {
                result.put(fieldName, value);
            }
        }
        return result;
    }

    private MvcData createPageMvcData(PageTemplate pageTemplate) {
        final String[] viewNameParts = getPageViewNameParts(pageTemplate);
        return MvcDataCreator.creator()
                .defaults(DefaultsMvcData.CORE_PAGE)
                .builder()
                .areaName(viewNameParts[0])
                .viewName(viewNameParts[1])
                .metadata(getMvcMetadata(pageTemplate))
                .build();
    }

    private MvcData createRegionMvcData(ComponentTemplate componentTemplate) {
        final String[] viewNameParts = getRegionViewNameParts(componentTemplate);
        return MvcDataCreator.creator()
                .defaults(DefaultsMvcData.CORE_REGION)
                .builder()
                .areaName(viewNameParts[0])
                .viewName(viewNameParts[1])
                .build();
    }

    private Map<String, Object> getMvcMetadata(PageTemplate pageTemplate) {

        Map<String, Object> metadata = new HashMap<>();
        Map<String, Field> metadataFields = pageTemplate.getMetadata();
        for (Map.Entry<String, Field> entry : metadataFields.entrySet()) {

            String fieldName = entry.getKey();
            if (fieldName.equals("view") ||
                    fieldName.equals("includes")) {
                continue;
            }
            Field field = entry.getValue();
            if (field.getFieldType() == FieldType.EMBEDDED) {
                // Output embedded field as List<Map<String,String>>
                //
                List<Map<String, String>> embeddedDataList = new ArrayList<>();
                for (Object value : field.getValues()) {
                    FieldSet fieldSet = (FieldSet) value;
                    Map<String, String> embeddedData = new HashMap<>();
                    for (String subFieldName : fieldSet.getContent().keySet()) {
                        Field subField = fieldSet.getContent().get(subFieldName);
                        if (!subField.getValues().isEmpty()) {
                            embeddedData.put(subFieldName, subField.getValues().get(0).toString());
                        }
                    }
                    embeddedDataList.add(embeddedData);
                }
                metadata.put(fieldName, embeddedDataList);
            } else {
                // Output other field types as single-value text fields
                //
                if (field.getValues().size() > 0) {
                    metadata.put(fieldName, field.getValues().get(0).toString()); // Assume single-value text fields for template metadata
                }
            }
        }
        return metadata;
    }

    private class DD4TRegionBuilderCallback implements RegionBuilderCallback {

        @Override
        public EntityModel buildEntity(Object source, Localization localization) throws ContentProviderException {
            ComponentPresentation componentPresentation = (ComponentPresentation) source;
            if (componentPresentation.isDynamic()) {
                try {
                    // Fetch the dynamic component presentation and replace the dummy static one
                    componentPresentation = dd4tComponentPresentationFactory.getComponentPresentation(
                            componentPresentation.getComponent().getId(), componentPresentation.getComponentTemplate().getId());
                } catch (Exception e) {
                    throw new ContentProviderException("Could not fetch dynamic component presentation.", e);
                }
            }
            return modelBuilderPipeline.createEntityModel(componentPresentation, localization);
        }

        @Override
        public String getRegionName(Object source) throws ContentProviderException {
            return PageBuilderImpl.getRegionName((ComponentPresentation) source);
        }

        @Override
        public MvcData getRegionMvcData(Object source) throws ContentProviderException {
            return createRegionMvcData(((ComponentPresentation) source).getComponentTemplate());
        }
    }
}
