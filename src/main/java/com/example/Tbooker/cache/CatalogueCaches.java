package com.example.Tbooker.cache;

public final class CatalogueCaches {
	public static final String MOVIES = "movies";
	public static final String MOVIE = "movie";
	public static final String THEATRES = "theatres";
	public static final String THEATRE = "theatre";
	public static final String SHOWS = "shows";
	public static final String SHOW = "show";
	// An enclosing write transaction must not read cached state or cache uncommitted data.
	public static final String OUTSIDE_TRANSACTION = "!T(org.springframework.transaction.support.TransactionSynchronizationManager).isActualTransactionActive()";
	private CatalogueCaches() { }
}
