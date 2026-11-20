package com.gameneko.aigame.api;

/** Bounded JSON representation of one engine-neutral action argument. */
public record ActionArgumentRequest(String name, String type, String text, Long integer,
                                    Double decimal, Boolean flag) {
}
