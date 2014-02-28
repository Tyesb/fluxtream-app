package com.fluxtream.connectors.google_calendar;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import com.fluxtream.SimpleTimeInterval;
import com.fluxtream.TimeInterval;
import com.fluxtream.TimeUnit;
import com.fluxtream.auth.AuthHelper;
import com.fluxtream.connectors.Connector;
import com.fluxtream.connectors.ObjectType;
import com.fluxtream.connectors.bodytrackResponders.AbstractBodytrackResponder;
import com.fluxtream.connectors.vos.AbstractFacetVO;
import com.fluxtream.domain.AbstractFacet;
import com.fluxtream.domain.ApiKey;
import com.fluxtream.domain.GuestSettings;
import com.fluxtream.mvc.models.TimespanModel;
import com.fluxtream.services.CoachingService;
import com.fluxtream.services.SettingsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * User: candide
 * Date: 19/10/13
 * Time: 00:25
 */
@Component
public class GoogleCalendarBodytrackResponder extends AbstractBodytrackResponder {

    @Autowired
    SettingsService settingsService;

    @Autowired
    CoachingService coachingService;

    @Override
    public List<TimespanModel> getTimespans(final long startMillis, final long endMillis, final ApiKey apiKey, final String channelName) {
        List<TimespanModel> items = new ArrayList<TimespanModel>();
        GoogleCalendarConnectorSettings connectorSettings = (GoogleCalendarConnectorSettings)settingsService.getConnectorSettings(apiKey.getId());
        final TimeInterval timeInterval = new SimpleTimeInterval(startMillis, endMillis, TimeUnit.ARBITRARY, TimeZone.getTimeZone("UTC"));
        String objectTypeName = apiKey.getConnector().getName() + "-event";
        List<AbstractFacet> facets = getFacetsInTimespan(timeInterval,apiKey,null);
        facets = coachingService.filterFacets(AuthHelper.getGuestId(), apiKey.getId(), facets);

        for (AbstractFacet facet : facets){
            GoogleCalendarEventFacet event = (GoogleCalendarEventFacet) facet;
            if (connectorSettings!=null) {
                final CalendarConfig calendarConfig = connectorSettings.getCalendar(event.calendarId);
                if (calendarConfig!=null&&!calendarConfig.hidden)
                    items.add(new TimespanModel(event.start, event.end, ((GoogleCalendarEventFacet)facet).calendarId, objectTypeName));
            } else
                items.add(new TimespanModel(event.start, event.end, ((GoogleCalendarEventFacet)facet).calendarId, objectTypeName));
        }
        return items;
    }

    @Override
    public List<AbstractFacetVO<AbstractFacet>> getFacetVOs(final GuestSettings guestSettings,
                                                            final ApiKey apiKey,
                                                            final String objectTypeName,
                                                            final long start, final long end,
                                                            final String value) {
        Connector connector = apiKey.getConnector();
        String[] objectTypeNameParts = objectTypeName.split("-");
        ObjectType objectType = null;
        for (ObjectType ot : connector.objectTypes()){
            if (ot.getName().equals(objectTypeNameParts[1])){
                objectType = ot;
                break;
            }
        }

        TimeInterval timeInterval = metadataService.getArbitraryTimespanMetadata(apiKey.getGuestId(), start, end).getTimeInterval();

        List<AbstractFacet> facets = getFacetsInTimespan(timeInterval,apiKey,objectType);
        List<AbstractFacet> filteredFacets = new ArrayList<AbstractFacet>();
        for (AbstractFacet facet : facets) {
            GoogleCalendarEventFacet event = (GoogleCalendarEventFacet) facet;
            if (event.calendarId.equals(value))
                filteredFacets.add(event);
        }

        List<AbstractFacetVO<AbstractFacet>> facetVOsForFacets = getFacetVOsForFacets(filteredFacets, timeInterval, guestSettings);
        return facetVOsForFacets;
    }
}
