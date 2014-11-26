package com.sdl.webapp.dd4t;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sdl.webapp.common.api.content.ContentProvider;
import com.sdl.webapp.common.api.content.ContentProviderException;
import com.sdl.webapp.common.api.content.NavigationProvider;
import com.sdl.webapp.common.api.content.NavigationProviderException;
import com.sdl.webapp.common.api.localization.Localization;
import com.sdl.webapp.common.api.model.entity.Link;
import com.sdl.webapp.common.api.model.entity.NavigationLinks;
import com.sdl.webapp.common.api.model.entity.SitemapItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Implementation of {@code NavigationProvider}.
 */
@Component
public class DD4TNavigationProvider implements NavigationProvider {

    private static final String NAVIGATION_MODEL_URL = "/navigation.json";

    private static final String TYPE_STRUCTURE_GROUP = "StructureGroup";

    private final ContentProvider contentProvider;

    private final ObjectMapper objectMapper;

    @Autowired
    public DD4TNavigationProvider(ContentProvider contentProvider, ObjectMapper objectMapper) {
        this.contentProvider = contentProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public NavigationLinks getTopNavigationLinks(String requestPath, Localization localization)
            throws NavigationProviderException {
        final SitemapItem navigationModel = getNavigationModel(localization);
        return new NavigationLinks(createLinksForVisibleItems(navigationModel.getItems()));
    }

    @Override
    public NavigationLinks getContextNavigationLinks(String requestPath, Localization localization)
            throws NavigationProviderException {
        final SitemapItem navigationModel = getNavigationModel(localization);
        final SitemapItem contextNavigationItem = findContextNavigationStructureGroup(navigationModel, requestPath);

        final List<Link> links = contextNavigationItem != null ?
                createLinksForVisibleItems(contextNavigationItem.getItems()) : Collections.<Link>emptyList();

        return new NavigationLinks(links);
    }

    private SitemapItem findContextNavigationStructureGroup(SitemapItem item, String requestPath) {
        if (item.getType().equals(TYPE_STRUCTURE_GROUP) && requestPath.startsWith(item.getUrl().toLowerCase())) {
            // Check if there is a matching subitem, if yes, then return it
            for (SitemapItem subItem : item.getItems()) {
                final SitemapItem matchingSubItem = findContextNavigationStructureGroup(subItem, requestPath);
                if (matchingSubItem != null) {
                    return matchingSubItem;
                }
            }

            // Otherwise return this matching item
            return item;
        }

        // No matching item
        return null;
    }

    @Override
    public NavigationLinks getBreadcrumbNavigationLinks(String requestPath, Localization localization)
            throws NavigationProviderException {
        final SitemapItem navigationModel = getNavigationModel(localization);

        final List<Link> links = new ArrayList<>();
        createBreadcrumbLinks(navigationModel, requestPath, links);

        return new NavigationLinks(links);
    }

    private boolean createBreadcrumbLinks(SitemapItem item, String requestPath, List<Link> links) {
        if (requestPath.startsWith(item.getUrl().toLowerCase())) {
            // Add link for this matching item
            links.add(createLinkForItem(item));

            // Add links for the following matching subitems
            for (SitemapItem subItem : item.getItems()) {
                if (createBreadcrumbLinks(subItem, requestPath, links)) {
                    return true;
                }
            }

            return true;
        }

        return false;
    }

    private SitemapItem getNavigationModel(Localization localization) throws NavigationProviderException {
        try {
            return objectMapper.readValue(contentProvider.getPageContent(NAVIGATION_MODEL_URL, localization),
                    SitemapItem.class);
        } catch (ContentProviderException | IOException e) {
            throw new NavigationProviderException("Exception while loading navigation model", e);
        }
    }

    private List<Link> createLinksForVisibleItems(Iterable<SitemapItem> items) {
        final List<Link> links = new ArrayList<>();
        for (SitemapItem item : items) {
            if (item.isVisible()) {
                links.add(createLinkForItem(item));
            }
        }
        return links;
    }

    private Link createLinkForItem(SitemapItem item) {
        final Link link = new Link();
        link.setUrl(item.getUrl());     // TODO: Resolve link
        link.setLinkText(item.getTitle());
        return link;
    }
}