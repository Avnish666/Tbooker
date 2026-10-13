package com.example.Tbooker.service;

import com.example.Tbooker.cache.CatalogueChanged;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class CatalogueCacheDependencies {
	private final EntityManager entities;
	public CatalogueCacheDependencies(EntityManager entities) { this.entities = entities; }
	// AFTER_COMMIT still has the old persistence context bound: use a fresh, short read transaction.
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public List<Long> affectedShows(CatalogueChanged change) {
		if (change.kind() == CatalogueChanged.Kind.SHOW) { return List.of(change.id()); }
		String predicate = switch (change.kind()) {
			case MOVIE -> "s.movie.id";
			case THEATRE -> "s.screen.theatre.id";
			case SCREEN -> "s.screen.id";
			case SHOW -> throw new IllegalStateException("Show handled above");
		};
		return entities.createQuery("select s.id from MovieShow s where " + predicate + " = :id", Long.class)
				.setParameter("id", change.id()).getResultList();
	}
}
