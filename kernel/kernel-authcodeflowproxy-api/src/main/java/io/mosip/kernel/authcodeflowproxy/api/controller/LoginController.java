package io.mosip.kernel.authcodeflowproxy.api.controller;

import io.mosip.kernel.authcodeflowproxy.api.validator.ValidateTokenUtil;
import io.mosip.kernel.core.http.ResponseFilter;
import io.mosip.kernel.core.http.ResponseWrapper;
import io.mosip.kernel.core.util.EmptyCheckUtils;
import io.mosip.kernel.openid.bridge.api.constants.Constants;
import io.mosip.kernel.openid.bridge.api.constants.Errors;
import io.mosip.kernel.openid.bridge.api.exception.ClientException;
import io.mosip.kernel.openid.bridge.api.exception.ServiceException;
import io.mosip.kernel.authcodeflowproxy.api.service.LoginServiceV2;
import io.mosip.kernel.openid.bridge.api.utils.JWTUtils;
import io.mosip.kernel.openid.bridge.dto.AccessTokenResponseDTO;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
public class LoginController {
	
	private static final String ID_TOKEN = "id_token";

	private final static Logger LOGGER= LoggerFactory.getLogger(LoginController.class);
	private static final String IDTOKEN = "idToken";

	@Value("${auth.token.header:Authorization}")
	private String authTokenHeader;
	
	@Value("${iam.locale.cookie.name:KEYCLOAK_LOCALE}")
	private String localeCookieName;
	
	@Value("${iam.locale.cookie.name:/auth/realms/}")
	private String localeCookiePath;
	
	
	@Value("#{'${auth.allowed.urls}'.split(',')}")
	private List<String> allowedUrls;

	@Autowired
	private LoginServiceV2 loginService;
	
	@Autowired
	private ValidateTokenUtil validateTokenHelper;

	@Autowired
	private Environment environment;

	@Value("${auth.validate.id-token:false}")
	private boolean validateIdToken;
	
	@Autowired
	private AntPathMatcher antPathMatcher;
	
	/**
	 * For offline logout, there is no token invalidation happening in the IdP's
	 * end. It is expected that the cookies with the tokens only getting expired.
	 */
	@Value("${mosip.iam.logout.offline:false}")
	private boolean offlineLogout;

	@GetMapping(value = "/login/{redirectURI}")
	public void login(@CookieValue(name = "state", required = false) String state,
			@PathVariable("redirectURI") String redirectURI,
			@RequestParam(name = "state", required = false) String stateParam, HttpServletResponse res)
			throws IOException {
		login(state, redirectURI, stateParam, null, res);
	}

	@SuppressWarnings({"java:S2092", "java:S3330"}) // added suppress for sonarcloud. The secure flag, httpOnly flag is set to true through setCookieParams method. Line # 111.
	@GetMapping(value = "/login/v2/{redirectURI}")
	public void login(@CookieValue(name = "state", required = false) String state,
			@PathVariable("redirectURI") String redirectURI,
			@RequestParam(name = "state", required = false) String stateParam, 
			@RequestParam(name = "ui_locales", required = false) String uiLocales, HttpServletResponse res)
			throws IOException {
		String stateValue = EmptyCheckUtils.isNullEmpty(state) ? stateParam : state;
		if (EmptyCheckUtils.isNullEmpty(stateValue)) {
			throw new ServiceException(Errors.STATE_NULL_EXCEPTION.getErrorCode(),
					Errors.STATE_NULL_EXCEPTION.getErrorMessage());
		}

		// there is no UUID.parse method till so using this as alternative
		try {
			if (!UUID.fromString(stateValue).toString().equals(stateValue)) {
				throw new ServiceException(Errors.STATE_NOT_UUID_EXCEPTION.getErrorCode(),
						Errors.STATE_NOT_UUID_EXCEPTION.getErrorMessage());
			}
		} catch (IllegalArgumentException exception) {
			throw new ServiceException(Errors.STATE_NOT_UUID_EXCEPTION.getErrorCode(),
					Errors.STATE_NOT_UUID_EXCEPTION.getErrorMessage());
		}
		
		String uri = loginService.loginV2(redirectURI, stateValue, uiLocales);
		Cookie stateCookie = new Cookie("state", stateValue);
		setCookieParams(stateCookie,true,true,"/");
		res.addCookie(stateCookie);
		res.setStatus(302);
		res.sendRedirect(uri);
	}

	@SuppressWarnings({"javasecurity:S5146", "java:S2092", "java:S3330"}) // added suppress for sonarcloud. The URLs whitelisting with the configured value in properties. Line # 156.
	// The secure flag, httpOnly flag is set to true through setCookieParams method. Line # 151.
	@GetMapping(value = "/login-redirect/{redirectURI}")
	public void loginRedirect(@PathVariable("redirectURI") String redirectURI, @RequestParam(value="state", required = false) String state,
			@RequestParam(value="session_state",required = false) String sessionState, @RequestParam(value="code", required = false) String code, 
			@RequestParam(value="error", required = false) String error,
			@CookieValue(value="state", required = false) String stateCookie, HttpServletRequest req, HttpServletResponse res) throws IOException {
		if(error == null || error.isEmpty()){
			AccessTokenResponseDTO jwtResponseDTO = loginService.loginRedirect(state, sessionState, code, stateCookie,
					redirectURI);
			String accessToken = jwtResponseDTO.getAccessToken();
			validateTokenHelper.validateToken(accessToken);
			Cookie cookie = loginService.createCookie(accessToken);
			res.addCookie(cookie);
			if(validateIdToken) {
				String subjectClaimNameProperty = this.environment.getProperty(Constants.TOKEN_SUBJECT_CLAIM_NAME);
				String authTokenSub =  JWTUtils.getSubClaimValueFromToken
						(cookie.getValue(), subjectClaimNameProperty);
				String idTokenProperty  = this.environment.getProperty(IDTOKEN, ID_TOKEN);
				String idToken = jwtResponseDTO.getIdToken();
				if(idToken == null) {
					LOGGER.error("Id token is null.");
					throw new ClientException(Errors.TOKEN_NOTPRESENT_ERROR.getErrorCode(),
							Errors.TOKEN_NOTPRESENT_ERROR.getErrorMessage() + ": " + idTokenProperty);
				}
				String idTokenSub = JWTUtils.getSubClaimValueFromToken(idToken,
						subjectClaimNameProperty);
				if(idTokenSub != null && !idTokenSub.equalsIgnoreCase(authTokenSub)){
					LOGGER.error("Id token Sub value and auth token sub value are not matching.");
					throw new ClientException(Errors.INVALID_TOKEN.getErrorCode(),
							Errors.INVALID_TOKEN.getErrorMessage());
				}
				validateTokenHelper.validateToken(idToken);
				Cookie idTokenCookie = new Cookie(idTokenProperty, idToken);
				setCookieParams(idTokenCookie,true,true,"/");
				res.addCookie(idTokenCookie);
			}
		}
		res.setStatus(302);
		String redirectUrl = new String(Base64.decodeBase64(redirectURI.getBytes()));
		
		boolean matchesAllowedUrls = matchesAllowedUrls(redirectUrl);
		if(!matchesAllowedUrls) {
			LOGGER.error("Url {} was not part of allowed url's", redirectUrl.replaceAll("[\n\r]", "_"));
			throw new ServiceException(Errors.ALLOWED_URL_EXCEPTION.getErrorCode(), Errors.ALLOWED_URL_EXCEPTION.getErrorMessage());
		}
		// If error exist appending that as a query param along with redirecturi
		if(error != null && !error.isEmpty()){
			redirectUrl = redirectUrl+"?error="+error;
		}
		res.sendRedirect(redirectUrl);	
	}



	private boolean matchesAllowedUrls(String url) {
		boolean hasMatch = allowedUrls.contains(url.contains("#") ? url.split("#")[0] : url);
		if(!hasMatch) {		
			hasMatch = allowedUrls.stream()
				.filter(pattern -> antPathMatcher.isPattern(pattern))
				.anyMatch(pattern -> antPathMatcher.match(pattern, url));
		}
		return hasMatch;
	}

	private void setCookieParams(Cookie idTokenCookie, boolean isHttpOnly, boolean isSecure,String path) {
		idTokenCookie.setHttpOnly(isHttpOnly);
		idTokenCookie.setSecure(isSecure);
		idTokenCookie.setPath(path);
	}

	@ResponseFilter
	@GetMapping(value = "/authorize/admin/validateToken")
	public ResponseWrapper<?> validateAdminToken(HttpServletRequest request, HttpServletResponse res) {
		String authToken = null;
		Cookie[] cookies = request.getCookies();
		if (cookies == null) {
			throw new ClientException(Errors.COOKIE_NOTPRESENT_ERROR.getErrorCode(),
					Errors.COOKIE_NOTPRESENT_ERROR.getErrorMessage());
		}
		Object mosipUserDto = null;

		for (Cookie cookie : cookies) {
			if (cookie.getName().contains(authTokenHeader)) {
				authToken = cookie.getValue();
			}
		}
		if (authToken == null) {
			throw new ClientException(Errors.TOKEN_NOTPRESENT_ERROR.getErrorCode(),
					Errors.TOKEN_NOTPRESENT_ERROR.getErrorMessage());
		}

		mosipUserDto = loginService.valdiateToken(authToken);
		Cookie cookie = loginService.createCookie(authToken);
		res.addCookie(cookie);
		ResponseWrapper<Object> responseWrapper = new ResponseWrapper<>();
		responseWrapper.setResponse(mosipUserDto);
		return responseWrapper;
	}
	
	@SuppressWarnings({"javasecurity:S5146", "java:S2092", "java:S3330"}) // added suppress for sonarcloud. The URLs whitelisting with the configured value in properties. Line # 221.
	// The secure flag, httpOnly flag is set to true through setCookieParams method. Line # 240.
	@ResponseFilter
	@GetMapping(value = "/logout/user")
	public void logoutUser(
			@CookieValue(value = "Authorization", required = false) String token,@RequestParam(name = "redirecturi", required = true) String redirectURI, HttpServletResponse res) throws IOException {
		String redirectURL = new String(Base64.decodeBase64(redirectURI));
		if(!matchesAllowedUrls(redirectURL)) {
			redirectURL = redirectURL.replaceAll("[\n\r]", " ");
			LOGGER.error("Url {} was not part of allowed url's", redirectURL.replaceAll("[\n\r]", "_"));
			throw new ServiceException(Errors.ALLOWED_URL_EXCEPTION.getErrorCode(), Errors.ALLOWED_URL_EXCEPTION.getErrorMessage());
		}
		String uri = loginService.logoutUser(token,redirectURI);
		
		if(offlineLogout) {
			Cookie cookie = loginService.createExpiringCookie();
			res.addCookie(cookie);
			
			if(validateIdToken) {
				String idTokenProperty  = this.environment.getProperty(IDTOKEN, ID_TOKEN);
				//Create expiring id_token cookie
				Cookie idTokenCookie = new Cookie(idTokenProperty, null);
				idTokenCookie.setMaxAge(0);
				setCookieParams(idTokenCookie,true,true,"/");
				res.addCookie(idTokenCookie);
			}
		}
		
		res.setStatus(302);
		res.sendRedirect(uri);
	}

}
