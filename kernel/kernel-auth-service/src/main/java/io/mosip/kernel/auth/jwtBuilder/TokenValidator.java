package io.mosip.kernel.auth.jwtBuilder;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.mosip.kernel.auth.config.MosipEnvironment;
import io.mosip.kernel.auth.entities.AuthToken;
import io.mosip.kernel.auth.entities.MosipUser;
import io.mosip.kernel.auth.entities.MosipUserDto;
import io.mosip.kernel.auth.entities.MosipUserDtoToken;
import io.mosip.kernel.auth.entities.MosipUserWithToken;
import io.mosip.kernel.auth.service.CustomTokenServices;

import java.util.Date;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.web.authentication.www.NonceExpiredException;
import org.springframework.stereotype.Component;

@Component
public class TokenValidator {

    @Autowired
    MosipEnvironment mosipEnvironment;
    
    @Autowired
    CustomTokenServices customTokenServices;

    private Boolean validateOtpDetails(Claims claims) {
        if (claims.get("isOtpRequired") == null) {
            return true;
        }

        Boolean isOtpVerified = (Boolean) claims.get("isOtpVerified");
        Boolean isOtpRequired = (Boolean) claims.get("isOtpRequired");
        if (isOtpRequired && !isOtpVerified) {
            return false;
        }
        return true;
    }

    private MosipUser buildMosipUser(Claims claims) {
        return new MosipUser(
                claims.getSubject(),
                (String) claims.get("mobile"),
                (String) claims.get("mail"),
                (String) claims.get("role")
        );
    }

    private Claims getClaims(String token) {
        String token_base = mosipEnvironment.getTokenBase();
        String secret = mosipEnvironment.getJwtSecret();

        if (token == null || !token.startsWith(token_base)) {
            throw new NonceExpiredException("Invalid Token");
        }

        try {
            Claims claims = Jwts.parser()
                    .setSigningKey(secret)
                    .parseClaimsJws(token.substring(token_base.length()))
                    .getBody();

            return claims;
        } catch (Exception e) {
            throw new RuntimeException("Invalid Token");
        }
    }

    public MosipUserWithToken validateForOtpVerification(String token) {
        Claims claims = getClaims(token);
        Boolean isOtpRequired = (Boolean) claims.get("isOtpRequired");
        if (isOtpRequired) {
            MosipUser mosipUser = buildMosipUser(claims);
            return new MosipUserWithToken(mosipUser, token);
        } else {
            throw new RuntimeException("Invalid Token");
        }
    }

    public MosipUserWithToken basicValidate(String token) {
        Claims claims = getClaims(token);
        Boolean isOtpValid = validateOtpDetails(claims);
        if (isOtpValid) {
            MosipUser mosipUser = buildMosipUser(claims);
            return new MosipUserWithToken(mosipUser, token);
        } else {
            throw new RuntimeException("Invalid Token");
        }
    }

	public MosipUserDtoToken validateToken(String token) {
		Claims claims = getClaims(token);
		
		MosipUserDto mosipUserDto = buildDto(claims);
/*		long currentTime = new Date().getTime();
		if(claims!=null && (currentTime<authToken.getExpirationTime()))
		{
			return true;
		}*/
		return new MosipUserDtoToken(mosipUserDto,token,null);
	}

	private MosipUserDto buildDto(Claims claims) {
		MosipUserDto mosipUserDto = new MosipUserDto();
		mosipUserDto.setName(claims.getSubject());
		mosipUserDto.setRole((String)claims.get("role"));
		mosipUserDto.setMail((String)claims.get("mail"));
		mosipUserDto.setMobile((String)claims.get("mobile"));
		return mosipUserDto;
	}

	public MosipUserDtoToken validateOTP(String otp) {
        Claims claims = getClaims(otp);
        Boolean isOtpRequired = (Boolean) claims.get("isOtpRequired");
        if (isOtpRequired) {
        	MosipUserDto mosipUserDto = buildDto(claims);
            return new MosipUserDtoToken(mosipUserDto, otp,null);
        } else {
            throw new RuntimeException("Invalid Token");
        }
    }

	public boolean validateExpiry(String token) {
		Claims claims = getClaims(token);
		if(claims!=null)
		{
			Integer expTime = (Integer)claims.get("exp");
			long currentTime = new Date().getTime();
			long exp = expTime.longValue()*1000;
			if(expTime!=0 && currentTime<exp)
			{
				return true;
			}
		}
		return false;
	}
}
