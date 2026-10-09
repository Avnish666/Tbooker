package com.example.Tbooker.repository;

import com.example.Tbooker.entity.MovieShow;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MovieShowRepository extends JpaRepository<MovieShow, Long> {

	@EntityGraph(attributePaths = {"movie", "screen", "screen.theatre"})
	List<MovieShow> findAllByOrderByStartTimeAscIdAsc();

	@Override
	@EntityGraph(attributePaths = {"movie", "screen", "screen.theatre"})
	Optional<MovieShow> findById(Long id);

}
