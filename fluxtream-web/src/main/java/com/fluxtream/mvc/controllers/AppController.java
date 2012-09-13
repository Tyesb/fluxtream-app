package com.fluxtream.mvc.controllers;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import com.fluxtream.Configuration;
import com.fluxtream.connectors.Connector;
import com.fluxtream.domain.Guest;
import com.fluxtream.domain.Notification.Type;
import com.fluxtream.services.ApiDataService;
import com.fluxtream.services.ConnectorUpdateService;
import com.fluxtream.services.GuestService;
import com.fluxtream.services.MetadataService;
import com.fluxtream.services.NotificationsService;
import com.fluxtream.utils.SecurityUtils;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class AppController {

	Logger logger = Logger.getLogger(AppController.class);

	@Autowired
	Configuration env;

	@Autowired
	GuestService guestService;

	@Autowired
	ApiDataService apiDataService;

	@Autowired
	MetadataService metadataService;

	@Autowired
	NotificationsService notificationsService;

    @Autowired
    ConnectorUpdateService connectorUpdateService;

    @Autowired
	BeanFactory beanFactory;

	@RequestMapping(value = { "", "/", "/welcome" })
	public ModelAndView index(HttpServletRequest request) {
		Authentication auth = SecurityContextHolder.getContext()
				.getAuthentication();
		if (auth != null && auth.isAuthenticated())
			return new ModelAndView("redirect:/app");
		ModelAndView mav = new ModelAndView("index");
		String release = env.get("release");
		if (release != null)
			mav.addObject("release", release);
		String targetEnvironment = env.get("environment");
		mav.addObject("tracker", hasTracker(request));
		return mav;
	}

    private boolean hasTracker(HttpServletRequest request) {
        String trackerPath = request.getSession().getServletContext().getRealPath("/WEB-INF/jsp/tracker.jsp");
        File trackerFile = new File(trackerPath);
        final boolean fileExists = trackerFile.exists();
        return fileExists;
    }

    @RequestMapping(value = { "/snippets" })
    public ModelAndView snippets(HttpServletRequest request) {

        long guestId = ControllerHelper.getGuestId();

        ModelAndView mav = new ModelAndView("snippets");
        String targetEnvironment = env.get("environment");
        mav.addObject("tracker", hasTracker(request));
        if (request.getSession(false) == null)
            return mav;

        Authentication auth = SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null || !auth.isAuthenticated())
            return mav;
        mav.setViewName("snippets");

        String release = env.get("release");
        mav.addObject("release", release);
        return mav;
    }

    @RequestMapping(value = { "/explorer" })
    public ModelAndView explorer(HttpServletRequest request) {

        long guestId = ControllerHelper.getGuestId();

        ModelAndView mav = new ModelAndView("snippets");
        String targetEnvironment = env.get("environment");
        mav.addObject("tracker", hasTracker(request));
        if (request.getSession(false) == null)
            return mav;

        Authentication auth = SecurityContextHolder.getContext()
                .getAuthentication();
        if (auth == null || !auth.isAuthenticated())
            return mav;
        mav.setViewName("explorer");

        String release = env.get("release");
        mav.addObject("release", release);
        return mav;
    }

    public ModelAndView home(HttpServletRequest request) {
		logger.info("action=loggedIn");

		long guestId = ControllerHelper.getGuestId();

		ModelAndView mav = new ModelAndView("redirect:main");
		String targetEnvironment = env.get("environment");
        mav.addObject("tracker", hasTracker(request));
		if (request.getSession(false) == null)
			return mav;

		Authentication auth = SecurityContextHolder.getContext()
				.getAuthentication();
		if (auth == null || !auth.isAuthenticated())
			return mav;
		mav.setViewName("home");

		Guest guest = guestService.getGuestById(guestId);

		mav.addObject("fullname", guest.getGuestName());

		String release = env.get("release");
		request.setAttribute("guestName", guest.getGuestName());
		if (SecurityUtils.isDemoUser())
			request.setAttribute("demo", true);
		if (release != null)
			mav.addObject("release", release);
		return mav;
	}

	@RequestMapping(value = { "/app*", "/app/**" })
	public ModelAndView welcomeHome(HttpServletRequest request)
			throws IOException, NoSuchAlgorithmException {
		if (!hasTimezoneCookie(request)||ControllerHelper.getGuest()==null)
			return new ModelAndView("redirect:/welcome");
		long guestId = ControllerHelper.getGuestId();
		checkIn(request, guestId);
		return home(request);
	}

	@RequestMapping(value = "/app/from/{connectorName}")
	public String home(HttpServletRequest request,
			@PathVariable("connectorName") String connectorName) {
		long guestId = ControllerHelper.getGuestId();
        final Connector connector = Connector.getConnector(connectorName);
        String message = "You have successfully added a new connector: "
				+ connector.prettyName()
				+ ". Your data is now being retrieved. "
				+ "It may take a little while until it becomes visible.";
		notificationsService.addNotification(guestId, Type.INFO, message);
        connectorUpdateService.updateConnector(guestId, connector, false);
		return "redirect:/app";
	}

	private void checkIn(HttpServletRequest request, long guestId)
			throws IOException {
		String remoteAddr = request.getHeader("X-Forwarded-For");
		if (remoteAddr == null)
			remoteAddr = request.getRemoteAddr();
        initializeWithTimeZone(request, guestId);
		guestService.checkIn(guestId, remoteAddr);
        connectorUpdateService.updateAllConnectors(guestId);
	}
	
	private boolean hasTimezoneCookie(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		for (Cookie cookie : cookies) {
			if (cookie.getName().equalsIgnoreCase("timeZone")) {
				return true;
			}
		}
		return false;
	}

	private void initializeWithTimeZone(HttpServletRequest request, long guestId) {
		Cookie[] cookies = request.getCookies();
		String timeZone = null, date = null;
		for (Cookie cookie : cookies) {
			if (cookie.getName().equalsIgnoreCase("timeZone")) {
				timeZone = cookie.getValue();
			} else if (cookie.getName().equalsIgnoreCase("date")) {
				date = cookie.getValue();
			}
		}

		metadataService.setTimeZone(guestId, date, timeZone);
	}
}
