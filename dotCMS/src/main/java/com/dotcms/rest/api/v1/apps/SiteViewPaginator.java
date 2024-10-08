package com.dotcms.rest.api.v1.apps;

import com.dotcms.rest.api.v1.apps.view.SiteView;
import com.dotcms.util.pagination.OrderDirection;
import com.dotcms.util.pagination.PaginationException;
import com.dotcms.util.pagination.PaginatorOrdered;
import com.dotmarketing.beans.Host;
import com.dotmarketing.business.APILocator;
import com.dotmarketing.business.PermissionAPI;
import com.dotmarketing.exception.DotDataException;
import com.dotmarketing.exception.DotRuntimeException;
import com.dotmarketing.exception.DotSecurityException;
import com.dotmarketing.portlets.contentlet.business.HostAPI;
import com.dotmarketing.portlets.contentlet.model.Contentlet;
import com.dotmarketing.util.Logger;
import com.dotmarketing.util.PaginatedArrayList;
import com.dotmarketing.util.UtilMethods;
import com.google.common.annotations.VisibleForTesting;
import com.liferay.portal.model.User;
import io.vavr.control.Try;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * PaginatorOrdered implementation for objects of type SiteView.
 * This Pagination isn't very typical in the sense that items are not retrieved from a database.
 * Sorting and filtering itself happens right here on top of the list of elements itself.
 */
public class SiteViewPaginator implements PaginatorOrdered<SiteView> {

    private final Supplier<Set<String>> configuredSitesSupplier;
    private final Supplier<Map<String, Map<String, List<String>>>> warningsBySiteSupplier;
    private final HostAPI hostAPI;
    private final PermissionAPI permissionAPI;

    @VisibleForTesting
    public SiteViewPaginator(final Supplier<Set<String>> configuredSitesSupplier,
        final Supplier<Map<String, Map<String, List<String>>>> warningsBySiteSupplier,
        final HostAPI hostAPI,
        final PermissionAPI permissionAPI) {
        this.configuredSitesSupplier = configuredSitesSupplier;
        this.warningsBySiteSupplier = warningsBySiteSupplier;
        this.hostAPI = hostAPI;
        this.permissionAPI = permissionAPI;
    }

    /**
     * This custom getItem implementation will extract join and apply filtering sorting and pagination.
     * @param user user to filter
     * @param filter extra filter parameter
     * @param limit Number of items to return
     * @param offset offset
     * @param orderBy This param is ignored.
     * @param direction This param is ignored.
     * @param extraParams This param is ignored.
     * @return SiteView pageItems.
     * @throws PaginationException
     */
    @Override
    public PaginatedArrayList<SiteView> getItems(final User user, final String filter,
            final int limit, final int offset,
            final String orderBy, final OrderDirection direction,
            final Map<String, Object> extraParams) throws PaginationException {
        try {
            //get all sites. system_host is lower cased here.
            final List<String> allSitesIdentifiers = getHostIdentifiers(user, filter);

            //These values are fed from the outside through the appsAPI.
            final Set<String> sitesWithConfigurations = configuredSitesSupplier.get().stream()
                    .map(String::toLowerCase).collect(Collectors.toSet());
            final LinkedHashSet<String> allSites = new LinkedHashSet<>(allSitesIdentifiers);
            final long totalCount = allSites.size();

            //By doing this we remove from the configured-sites collection whatever sites didn't match the search.
            //If it isn't part of the search results also discard from the configured sites we intend to show.
            final LinkedHashSet<String> configuredSites = sitesWithConfigurations.stream()
                    .filter(allSites::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));

            final List<String> finalList = join(configuredSites, allSitesIdentifiers).stream()
                    .skip(offset).limit(limit).collect(Collectors.toList());

            final Map<String, Map<String, List<String>>> warningsBySite = warningsBySiteSupplier
                    .get();

            //And finally load from db and map into the desired view.
            final List<SiteView> siteViews = finalList.stream().map(id -> {
                try {
                    return hostAPI.find(id, user, false);
                } catch (DotDataException | DotSecurityException e) {
                    Logger.error(SiteViewPaginator.class, e);
                }
                return null;
            }).filter(Objects::nonNull).map(host -> {
                final String siteId = host.getIdentifier().toLowerCase();
                final Map<String, List<String>> secretsWithWarnings = warningsBySite.get(siteId);
                final int secretsWithWarningsCount = null != secretsWithWarnings ? secretsWithWarnings.size() : 0;
                final boolean configured = configuredSites.contains(siteId);
                return new SiteView(host.getIdentifier(), host.getName(), configured, secretsWithWarningsCount);
            }).collect(Collectors.toList());

            //And then we're done and out of here.
            final PaginatedArrayList<SiteView> paginatedArrayList = new PaginatedArrayList<>();
            paginatedArrayList.setTotalResults(totalCount);
            paginatedArrayList.addAll(siteViews);
            return paginatedArrayList;

        } catch (Exception e) {
            Logger.error(SiteViewPaginator.class, e.getMessage(), e);
            throw new DotRuntimeException(e);
        }
    }

    /**
     * This join method expects two collections of host identifiers.
     * First-one the Set coming from the serviceIntegrations-API containing all the sites with configurations.
     * Second-one a List with all the sites coming from querying the index.
     * Meaning this list is expected to come filtered and sorted.
     * The resulting list will have all the configured items first and then he rest of the entries.
     * Additionally to that SYSTEM_HOST is always expected to appear first if it ever existed on the allSites list.
     * (Cuz it could have been removed from applying filtering).
     * @param configuredSites sites with configurations coming from service Integration API.
     * @param allSites all-sites sorted and filtered loaded from ES
     * @return a brand new List ordered.
     */
    private List<String> join(final Set<String> configuredSites, final List<String> allSites) {
        final List<String> newList = new LinkedList<>();
        boolean systemHostFound = false;
        int index = 0;
        for (final String siteIdentifier : allSites) {
            if (!siteIdentifier.equalsIgnoreCase(Host.SYSTEM_HOST)) {
                if (configuredSites.contains(siteIdentifier)) {
                    newList.add(index++, siteIdentifier);
                } else {
                    newList.add(siteIdentifier);
                }
            } else {
                systemHostFound = true;
            }
        }
        if (systemHostFound) {
            newList.add(0, Host.SYSTEM_HOST);
        }
        return newList;
    }

    /**
     * Load all host identifiers
     * includes permissions into account.
     * So it is very performant.
     * The results are returned by default in order Ascendant order by site name.
     * This is very important cause any comparator applied must respect that.
     * @param user logged-in user
     * @param filter a string to match against the title.
     * @return
     * @throws DotDataException
     * @throws DotSecurityException
     */
     List<String> getHostIdentifiers(final User user, final String filter)
            throws DotDataException, DotSecurityException {

        Stream<Host> hostStream = hostAPI.findAllFromCache(user, false).stream()
                .filter(Objects::nonNull).filter(host -> null != host.getHostname())
                .filter(host -> Try.of(() -> !host.isArchived()).getOrElse(false));

        if (UtilMethods.isSet(filter)) {
            final String regexFilter = "(?i).*"+filter+"(.*)";
            hostStream = hostStream.filter(host -> host.getHostname().matches(regexFilter));
        }
        return hostStream.filter(host -> Try.of(() -> permissionAPI
                .doesUserHavePermission(host, PermissionAPI.PERMISSION_READ, user))
                .getOrElse(false)).sorted(Comparator.comparing(Host::getHostname))
                .map(Contentlet::getIdentifier).filter(Objects::nonNull).map(String::toLowerCase)
                .collect(Collectors.toList());

    }

}
