package com.example.Tbooker.cache;

import com.example.Tbooker.entity.Movie;
import com.example.Tbooker.entity.Theatre;
import com.example.Tbooker.entity.Screen;
import com.example.Tbooker.entity.MovieShow;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Hibernate uses Spring's BeanContainer to supply the constructor dependency. */
@Component
public class CatalogueChangeListener {
	private final ApplicationEventPublisher events;
	public CatalogueChangeListener(ApplicationEventPublisher events) { this.events = events; }
	@PostPersist @PostUpdate @PostRemove
	public void changed(Object entity) {
		CatalogueChanged change;
		if (entity instanceof Movie movie) { change = new CatalogueChanged(CatalogueChanged.Kind.MOVIE, movie.getId()); }
		else if (entity instanceof Theatre theatre) { change = new CatalogueChanged(CatalogueChanged.Kind.THEATRE, theatre.getId()); }
		else if (entity instanceof Screen screen) { change = new CatalogueChanged(CatalogueChanged.Kind.SCREEN, screen.getId()); }
		else if (entity instanceof MovieShow show) { change = new CatalogueChanged(CatalogueChanged.Kind.SHOW, show.getId()); }
		else { throw new IllegalArgumentException("Unsupported catalogue entity"); }
		events.publishEvent(change);
	}
}
