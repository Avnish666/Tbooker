package com.example.Tbooker.repository;

import com.example.Tbooker.entity.Movie;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MovieRepository extends JpaRepository<Movie, Long> {
	List<Movie> findAllByOrderByIdAsc();
}
