package com.mcaia.plugin.util;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;

/** MiniMessage with formatting tags only; click, hover, selector, and NBT tags stay disabled. */
public final class SafeMiniMessage {

    public static final MiniMessage INSTANCE = MiniMessage.builder().tags(TagResolver.builder()
            .resolver(StandardTags.color())
            .resolver(StandardTags.decorations())
            .resolver(StandardTags.gradient())
            .resolver(StandardTags.rainbow())
            .resolver(StandardTags.transition())
            .resolver(StandardTags.reset())
            .build()).build();

    private SafeMiniMessage() {}
}
