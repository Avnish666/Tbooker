package com.example.Tbooker.repository;

import com.example.Tbooker.entity.Theatre;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TheatreRepository extends JpaRepository<Theatre, Long> {
	List<Theatre> findAllByOrderByIdAsc();
}
