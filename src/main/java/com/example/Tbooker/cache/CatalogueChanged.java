package com.example.Tbooker.cache;

public record CatalogueChanged(Kind kind, Long id) {
	public enum Kind { MOVIE, THEATRE, SCREEN, SHOW }
}
