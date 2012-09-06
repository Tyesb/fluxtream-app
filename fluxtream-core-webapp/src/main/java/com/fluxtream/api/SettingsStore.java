package com.fluxtream.api;

import java.io.IOException;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import com.fluxtream.domain.Guest;
import com.fluxtream.domain.GuestSettings;
import com.fluxtream.mvc.controllers.ControllerHelper;
import com.fluxtream.mvc.models.SettingsModel;
import com.fluxtream.mvc.models.StatusModel;
import com.fluxtream.services.GuestService;
import com.fluxtream.services.SettingsService;
import com.google.gson.Gson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import static com.newrelic.api.agent.NewRelic.setTransactionName;

/**
 *
 * @author Candide Kemmler (candide@fluxtream.com)
 */

@Path("/settings")
@Component("RESTSettingsStore")
@Scope("request")
public class SettingsStore {

    @Autowired
    GuestService guestService;

    @Autowired
    SettingsService settingsService;

    private final Gson gson = new Gson();

    @GET
    @Produces({ MediaType.APPLICATION_JSON })
    public String getSettings() {
        try{
            Guest guest = ControllerHelper.getGuest();
            GuestSettings settings = settingsService.getSettings(guest.getId());
            return gson.toJson(new SettingsModel(settings,guest));
        }
        catch (Exception e){
            return gson.toJson(new StatusModel(false,"Failed to get settings: " + e.getMessage()));
        }
    }

    @POST
    @Produces({ MediaType.APPLICATION_JSON })
    public String saveSettings(@FormParam("guest_firstname") String firstName, @FormParam("guest_lastname") String lastName,
                               @FormParam("length_measure_unit") String lengthUnit, @FormParam("distance_measure_unit") String distanceUnit,
                               @FormParam("weight_measure_unit") String weightUnit, @FormParam("temperature_unit") String temperatureUnit) throws IOException {
        setTransactionName(null, "POST /settings");
        try{
            GuestSettings.LengthMeasureUnit lngUnt = Enum.valueOf(
                    GuestSettings.LengthMeasureUnit.class, lengthUnit);
            GuestSettings.DistanceMeasureUnit dstUnt = Enum.valueOf(
                    GuestSettings.DistanceMeasureUnit.class, distanceUnit);
            GuestSettings.WeightMeasureUnit whtUnt = Enum.valueOf(
                    GuestSettings.WeightMeasureUnit.class, weightUnit);
            GuestSettings.TemperatureUnit tempUnt = Enum.valueOf(
                    GuestSettings.TemperatureUnit.class, temperatureUnit);

            long guestId = ControllerHelper.getGuestId();

            settingsService.setLengthMeasureUnit(guestId, lngUnt);
            settingsService.setDistanceMeasureUnit(guestId, dstUnt);
            settingsService.setWeightMeasureUnit(guestId, whtUnt);
            settingsService.setTemperatureUnit(guestId, tempUnt);

            settingsService.setFirstname(guestId, firstName);
            settingsService.setLastname(guestId, lastName);
            StatusModel status = new StatusModel(true, "settings updated!");
            return gson.toJson(status);
        }
        catch (Exception e){
            return gson.toJson(new StatusModel(false,"Failed to save settings: " + e.getMessage()));
        }
    }


}
