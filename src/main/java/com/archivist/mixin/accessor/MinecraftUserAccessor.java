package com.archivist.mixin.accessor;

import com.mojang.authlib.minecraft.UserApiService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for swapping the active Minecraft user at runtime.
 * Used by the Account Manager to switch between stored accounts.
 */
@Mixin(Minecraft.class)
public interface MinecraftUserAccessor {

    @Accessor("user")
    User archivist$getUser();

    @Accessor("user")
    @Mutable
    void archivist$setUser(User user);

    @Accessor("userApiService")
    UserApiService archivist$getUserApiService();

    @Accessor("userApiService")
    @Mutable
    void archivist$setUserApiService(UserApiService service);

    @Accessor("profileKeyPairManager")
    ProfileKeyPairManager archivist$getProfileKeyPairManager();

    @Accessor("profileKeyPairManager")
    @Mutable
    void archivist$setProfileKeyPairManager(ProfileKeyPairManager manager);
}
