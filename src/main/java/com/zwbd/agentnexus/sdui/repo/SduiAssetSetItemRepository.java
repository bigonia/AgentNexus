package com.zwbd.agentnexus.sdui.repo;

import com.zwbd.agentnexus.sdui.model.SduiAssetSetItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SduiAssetSetItemRepository extends JpaRepository<SduiAssetSetItem, String> {
    @Query("""
            select i from SduiAssetSetItem i
            join fetch i.assetSet s
            join fetch i.asset a
            where s.id = :assetSetId
            order by i.itemOrder asc, i.createdAt asc
            """)
    List<SduiAssetSetItem> findByAssetSet_IdOrderByItemOrderAscCreatedAtAsc(@Param("assetSetId") String assetSetId);

    Optional<SduiAssetSetItem> findByIdAndAssetSet_Id(String itemId, String assetSetId);

    boolean existsByAssetSet_IdAndAsset_Id(String assetSetId, String assetId);

    @Query("""
            select i from SduiAssetSetItem i
            join fetch i.assetSet s
            join fetch i.asset a
            where s.id in :assetSetIds
            order by s.id asc, i.itemOrder asc, i.createdAt asc
            """)
    List<SduiAssetSetItem> findByAssetSet_IdInOrderByAssetSet_IdAscItemOrderAscCreatedAtAsc(@Param("assetSetIds") List<String> assetSetIds);
}
