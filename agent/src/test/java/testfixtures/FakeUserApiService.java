package testfixtures;

import com.mojang.authlib.minecraft.UserApiService;

import java.util.HashMap;
import java.util.Set;

// A minimal concrete properties() implementation, standing in for whatever real class authlib's
// UserApiService implementation actually is at runtime.
public class FakeUserApiService {
    private final UserApiService.UserProperties properties;

    public FakeUserApiService(Set<UserApiService.UserFlag> flags) {
        this.properties = new UserApiService.UserProperties(flags, new HashMap<>());
    }

    public UserApiService.UserProperties properties() {
        return properties;
    }
}
