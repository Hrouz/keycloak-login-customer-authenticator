package com.hrouz.tuto.keycloak.auth;

import jakarta.ws.rs.core.MultivaluedHashMap;
import lombok.extern.jbosslog.JBossLog;

import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.forms.login.LoginFormsProvider;
import org.keycloak.models.*;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.services.ServicesLogger;
import org.keycloak.services.managers.AuthenticationManager;
import org.keycloak.services.managers.BruteForceProtector;
import org.keycloak.authentication.authenticators.browser.AbstractUsernameFormAuthenticator;

import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;


@JBossLog
public class CustomAuthenticatorForm extends AbstractUsernameFormAuthenticator implements Authenticator {

    @Override
    public void action(AuthenticationFlowContext context) {
        log.info("-------Call CustomAuthenticatorForm With Three fields authenticate----------");
        MultivaluedMap<String, String> formData = context.getHttpRequest().getDecodedFormParameters();
        if (formData.containsKey("cancel")) {
            context.cancelLogin();
        } else if (validateForm(context, formData)) {
            log.info("Login-------success");
            context.success();
        }
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>();
        String loginHint = context.getAuthenticationSession().getClientNote("login_hint");
        String rememberMeUsername = AuthenticationManager.getRememberMeUsername(context.getSession());
        if (context.getUser() != null) {
            LoginFormsProvider form = context.form();
            form.setAttribute("usernameHidden", true);
            form.setAttribute("registrationDisabled", true);
            context.getAuthenticationSession().setAuthNote("USER_SET_BEFORE_USERNAME_PASSWORD_AUTH", "true");
        } else {
            context.getAuthenticationSession().removeAuthNote("USER_SET_BEFORE_USERNAME_PASSWORD_AUTH");
            if (loginHint != null || rememberMeUsername != null) {
                if (loginHint != null) {
                    formData.add("username", loginHint);
                } else {
                    formData.add("username", rememberMeUsername);
                    formData.add("rememberMe", "on");
                }
            }
        }

        UserModel user = context.getUser();

        // Store the session in the context
        context.setUser(user);
        context.success();


        Response challengeResponse = challenge(context, formData);
        context.challenge(challengeResponse);
    }


    /**
     * validate username and password values entered
     *
     * @param context
     * @param formData
     * @return
     */
    private boolean validateForm(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        log.info("-------Call ValidateForm----------");
        String input_username = formData.getFirst(CustomAuthenticatorConstants.USER_NAME);
        String input_password = formData.getFirst(CustomAuthenticatorConstants.PASSWORD);
        log.info("input username : " + input_username);
        log.info("input password: " + input_password);
        return validateUserAndPassword(context, formData);
    }

    @Override
    public boolean validateUserAndPassword(AuthenticationFlowContext context, MultivaluedMap<String, String> inputData) {

        log.info("-------Call ValidateUserAndPassword----------");
        UserModel user = getUser(context, inputData);
        if (user != null) {
            boolean shouldClearUserFromCtxAfterBadPassword = !this.isUserAlreadySetBeforeUsernamePasswordAuth(context);
            boolean validatePassword = this.validatePassword(context, user, inputData, shouldClearUserFromCtxAfterBadPassword);
            return validatePassword && this.validateUser(context, user, inputData);
        } else {
            context.getEvent().error("user_not_found");
            context.failure(AuthenticationFlowError.UNKNOWN_USER, context.form()
                    .setError("user_not_found.").createLoginUsernamePassword());
            return false;
        }
    }


    private Response challenge(AuthenticationFlowContext context, MultivaluedMap<String, String> formData) {
        LoginFormsProvider forms = context.form();
        if (!formData.isEmpty()) forms.setFormData(formData);
        return forms.createLoginUsernamePassword();
    }


    /**
     * handlerBruteForce : Not used
     * we keep the generic message of keycloak to avoid the security issues
     *
     * @param context
     */
    private void handlerBruteForce(AuthenticationFlowContext context) {
        log.info("-------call handlerBruteForce----------");
        KeycloakSession session = context.getSession();
        RealmModel realm = context.getRealm();
        UserModel user = context.getUser();
        BruteForceProtector bruteForceProtector = session.getProvider(BruteForceProtector.class);

        // Check if the user is temporarily blocked
        if (user != null && bruteForceProtector.isTemporarilyDisabled(session, realm, user)) {
            // Display a custom message when the user is blocked
            context.failure(AuthenticationFlowError.USER_TEMPORARILY_DISABLED, context.form().setError("Your account is temporarily disabled due to too many failed login attempts. Please try again later.").createLoginUsernamePassword());
        }
    }


    /**
     * isGovernmentId : If the input is number then it`s GovernmentId
     *
     * @param input
     * @return
     */
    private boolean isGovernmentId(String input) {
        String regex = "\\d+";
        if (input != null) {
            return input.matches(regex);
        }
        return false;
    }

    /**
     * Find User by Customer ID (Government ID)
     *
     * @param context
     * @param customerId
     * @return
     */
    private UserModel findUserByCustomerId(AuthenticationFlowContext context, String customerId) {
        return context.getSession().users().searchForUserByUserAttributeStream(context.getRealm(),
                CustomAuthenticatorConstants.GOVERNMENT_ID, customerId).findFirst().orElse(null);
    }


    private UserModel getUser(AuthenticationFlowContext context, MultivaluedMap<String, String> inputData) {
        if (this.isUserAlreadySetBeforeUsernamePasswordAuth(context)) {
            UserModel user = context.getUser();
            this.testInvalidUser(context, user);
            return user;
        } else {
            context.clearUser();
            return getCustomUserFromForm(context, inputData);
        }
    }

    private UserModel getCustomUserFromForm(AuthenticationFlowContext context, MultivaluedMap<String, String> inputData) {
        String username = inputData.getFirst(CustomAuthenticatorConstants.USER_NAME);
        Response userResponse;
        if (username != null && !username.isEmpty()) {
            username = username.trim();
            context.getEvent().detail(CustomAuthenticatorConstants.USER_NAME, username);
            context.getAuthenticationSession().setAuthNote("ATTEMPTED_USERNAME", username);

            UserModel user = null;
            try {
                if (isGovernmentId(username)) {
                    log.info("Find user by government ID: " + username);
                    user = findUserByCustomerId(context, username);
                } else {
                    log.info("Find user by username/email : " + username);
                    user = KeycloakModelUtils.findUserByNameOrEmail(context.getSession(), context.getRealm(), username);
                }

            } catch (ModelDuplicateException var6) {
                ModelDuplicateException mde = var6;
                ServicesLogger.LOGGER.modelDuplicateException(mde);
                if (mde.getDuplicateFieldName() != null && mde.getDuplicateFieldName().equals("email")) {
                    this.setDuplicateUserChallenge(context, "email_in_use", "emailExistsMessage", AuthenticationFlowError.INVALID_USER);
                } else {
                    this.setDuplicateUserChallenge(context, "username_in_use", "usernameExistsMessage", AuthenticationFlowError.INVALID_USER);
                }

                return user;
            }

            this.testInvalidUser(context, user);
            return user;
        } else {
            context.getEvent().error("user_not_found");
            userResponse = this.challenge(context, this.getDefaultChallengeMessage(context), CustomAuthenticatorConstants.USER_NAME);
            context.failureChallenge(AuthenticationFlowError.INVALID_USER, userResponse);
            return null;
        }

    }

    private boolean validateUser(AuthenticationFlowContext context, UserModel user, MultivaluedMap<String, String> inputData) {
        if (!this.enabledUser(context, user)) {
            return false;
        } else {
            String rememberMe = inputData.getFirst("rememberMe");
            boolean remember = context.getRealm().isRememberMe() && rememberMe != null && rememberMe.equalsIgnoreCase("on");
            if (remember) {
                context.getAuthenticationSession().setAuthNote("remember_me", "true");
                context.getEvent().detail("remember_me", "true");
            } else {
                context.getAuthenticationSession().removeAuthNote("remember_me");
            }

            context.setUser(user);
            return true;
        }
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // never called
        return true;
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // never called
    }

    @Override
    public void close() {
    }
}
