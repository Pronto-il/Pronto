package com.pronto.favorites.repository;

import com.pronto.favorites.entity.Favorite;
import com.pronto.favorites.entity.FavoriteId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FavoriteRepository extends JpaRepository<Favorite, FavoriteId> {

    boolean existsByCustomerIdAndProfessionalId(Long customerId, Long professionalId);

    List<Favorite> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    void deleteByCustomerIdAndProfessionalId(Long customerId, Long professionalId);
}
