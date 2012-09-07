package com.fluxtream.api;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.SortedSet;
import java.util.TimeZone;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import com.fluxtream.TimeInterval;
import com.fluxtream.TimeUnit;
import com.fluxtream.domain.Guest;
import com.fluxtream.domain.metadata.DayMetadataFacet;
import com.fluxtream.mvc.models.PhotoModel;
import com.fluxtream.mvc.models.StatusModel;
import com.fluxtream.services.GuestService;
import com.fluxtream.services.MetadataService;
import com.fluxtream.services.PhotoService;
import com.fluxtream.services.SettingsService;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.newrelic.api.agent.NewRelic.setTransactionName;

@Path("/guest/{username}/photo")
@Component("RESTPhotoStore")
@Scope("request")
public class PhotoStore {

    private Gson gson = new Gson();

    @Autowired
    SettingsService settingsService;

    @Autowired
    GuestService guestService;

    @Autowired
    PhotoService photoService;

    @Autowired
    MetadataService metadataService;

    @GET
    @Path("/date/{date}")
    @Produces({MediaType.APPLICATION_JSON})
    public String getPhotosForDate(@PathParam("username") String username, @PathParam("date") String date){
        setTransactionName(null, "GET /guest/{username}/photo/date/{date}");
        try{
            Guest guest = guestService.getGuest(username);
            DayMetadataFacet dayMeta = metadataService.getDayMetadata(guest.getId(), date, true);
            return gson.toJson(getPhotos(guest, dayMeta.getTimeInterval()));
        } catch (Exception e){
            StatusModel result = new StatusModel(false, "Could not get guest addresses: " + e.getMessage());
            return gson.toJson(result);
        }
    }

    @GET
    @Path("/week/{year}/{week}")
    @Produces({MediaType.APPLICATION_JSON})
    public String getPhotosForWeek(@PathParam("username") String username, @PathParam("year") int year, @PathParam("week") int week){
        setTransactionName(null, "GET /guest/{username}/photo/week/{year}/{week}");
        try{
            Calendar c = Calendar.getInstance();
            c.set(Calendar.YEAR,year);
            c.set(Calendar.WEEK_OF_YEAR,week);
            c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY);
            Guest guest = guestService.getGuest(username);
            DecimalFormat datePartFormat = new DecimalFormat("00");
            DayMetadataFacet dayMetaStart = metadataService.getDayMetadata(guest.getId(), year + "-" + datePartFormat.format(c.get(Calendar.MONTH) + 1) +
                                                                                          "-" + datePartFormat.format(c.get(Calendar.DAY_OF_MONTH)), true);
            int newDay = c.get(Calendar.DAY_OF_YEAR) + 6;
            if (newDay > (isLeapYear(year) ? 366 : 365)){
                newDay -= isLeapYear(year) ? 366 : 365;
                year += 1;
                c.set(Calendar.YEAR,year);
            }
            c.set(Calendar.DAY_OF_YEAR,newDay);
            DayMetadataFacet dayMetaEnd = metadataService.getDayMetadata(guest.getId(), year + "-" + datePartFormat.format(c.get(Calendar.MONTH) + 1) +
                                                                                          "-" + datePartFormat.format(c.get(Calendar.DAY_OF_MONTH)), true);
            return gson.toJson(getPhotos(guest, new TimeInterval(dayMetaStart.start,dayMetaEnd.end,TimeUnit.WEEK,TimeZone.getTimeZone(dayMetaStart.timeZone))));
        } catch (Exception e){
            StatusModel result = new StatusModel(false, "Could not get photos: " + e.getMessage());
            return gson.toJson(result);
        }
    }

    @GET
    @Path("/year/{year}")
    @Produces({MediaType.APPLICATION_JSON})
    public String getPhotosForYear(@PathParam("username") String username, @PathParam("year") int year){
        setTransactionName(null, "GET /guest/{username}/photo/year/{year}");
        try{

            Guest guest = guestService.getGuest(username);
            DayMetadataFacet dayMetaStart = metadataService.getDayMetadata(guest.getId(), year + "-01-01", true);

            DayMetadataFacet dayMetaEnd = metadataService.getDayMetadata(guest.getId(), year + "-12-31", true);
            return gson.toJson(getPhotos(guest, new TimeInterval(dayMetaStart.start,dayMetaEnd.end,TimeUnit.WEEK,TimeZone.getTimeZone(dayMetaStart.timeZone))));
        } catch (Exception e){
            StatusModel result = new StatusModel(false, "Could not get photos: " + e.getMessage());
            return gson.toJson(result);
        }

    }

    private boolean isLeapYear(int year){
        if (year % 400 == 0)
            return true;
        if (year % 100 == 0)
            return false;
        return year % 4 == 0;
    }

    private List<PhotoModel> getPhotos(Guest guest, TimeInterval timeInterval) throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        final SortedSet<PhotoService.Photo> photos = photoService.getPhotos(guest.getId(), timeInterval);

        List<PhotoModel> photoModels = new ArrayList<PhotoModel>();
        for (final PhotoService.Photo photo : photos) {
            photoModels.add(new PhotoModel(photo.getAbstractPhotoFacetVO()));
        }

        return photoModels;
    }
}
