package welcomehome.auth;

import java.util.Locale;

import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Resolves {@link CurrentOwner} parameters. An authenticated user without an owner row, for example one
 * deleted while signed in, gets 403.
 */
class CurrentOwnerArgumentResolver implements HandlerMethodArgumentResolver {

    private final OwnerAccountDao owners;

    CurrentOwnerArgumentResolver(OwnerAccountDao owners) {
        this.owners = owners;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == CurrentOwner.class;
    }

    @Override
    public CurrentOwner resolveArgument(MethodParameter parameter, @Nullable ModelAndViewContainer mavContainer,
                                        NativeWebRequest webRequest, @Nullable WebDataBinderFactory binderFactory) {
        var principal = webRequest.getUserPrincipal();
        if (principal == null) {
            throw new AccessDeniedException("Not signed in");
        }
        return owners.findByEmail(principal.getName().toLowerCase(Locale.ROOT))
                .map(owner -> new CurrentOwner(owner.id(), owner.email()))
                .orElseThrow(() -> new AccessDeniedException("No owner for the signed-in user"));
    }
}
