package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.sdui.dto.SduiAddAssetSetItemsRequest;
import com.zwbd.agentnexus.sdui.dto.SduiCreateAssetSetRequest;
import com.zwbd.agentnexus.sdui.model.SduiAsset;
import com.zwbd.agentnexus.sdui.model.SduiAssetSet;
import com.zwbd.agentnexus.sdui.model.SduiAssetSetItem;
import com.zwbd.agentnexus.sdui.repo.SduiAssetRepository;
import com.zwbd.agentnexus.sdui.repo.SduiAssetSetItemRepository;
import com.zwbd.agentnexus.sdui.repo.SduiAssetSetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SduiAssetSetService {

    private static final int MAX_PROMPT_SET_COUNT = 5;

    private final SduiAssetSetRepository assetSetRepository;
    private final SduiAssetSetItemRepository assetSetItemRepository;
    private final SduiAssetRepository assetRepository;

    @Transactional
    public SduiAssetSet create(SduiCreateAssetSetRequest request) {
        SduiAssetSet set = new SduiAssetSet();
        set.setName(request.name().trim());
        set.setDescription(request.description() == null ? "" : request.description().trim());
        set.setTags(request.tags() == null ? new ArrayList<>() : new ArrayList<>(request.tags()));
        return assetSetRepository.save(set);
    }

    @Transactional(readOnly = true)
    public List<SduiAssetSet> list(Optional<String> keyword) {
        return keyword.filter(s -> !s.isBlank())
                .map(assetSetRepository::findByNameContainingIgnoreCase)
                .orElseGet(assetSetRepository::findAll);
    }

    @Transactional
    public List<SduiAssetSetItem> addAssets(String assetSetId, SduiAddAssetSetItemsRequest request) {
        SduiAssetSet assetSet = assetSetRepository.findById(assetSetId)
                .orElseThrow(() -> new IllegalArgumentException("asset set not found"));
        List<SduiAssetSetItem> current = assetSetItemRepository.findByAssetSet_IdOrderByItemOrderAscCreatedAtAsc(assetSetId);
        int orderSeed = current.stream()
                .map(SduiAssetSetItem::getItemOrder)
                .max(Integer::compareTo)
                .orElse(0);
        List<SduiAssetSetItem> created = new ArrayList<>();
        for (String assetId : request.assetIds()) {
            if (assetId == null || assetId.isBlank()) {
                continue;
            }
            if (assetSetItemRepository.existsByAssetSet_IdAndAsset_Id(assetSetId, assetId)) {
                continue;
            }
            SduiAsset asset = assetRepository.findById(assetId)
                    .orElseThrow(() -> new IllegalArgumentException("asset not found: " + assetId));
            SduiAssetSetItem item = new SduiAssetSetItem();
            item.setAssetSet(assetSet);
            item.setAsset(asset);
            item.setItemOrder(++orderSeed);
            created.add(assetSetItemRepository.save(item));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public List<SduiAssetSetItem> items(String assetSetId) {
        assetSetRepository.findById(assetSetId).orElseThrow(() -> new IllegalArgumentException("asset set not found"));
        return assetSetItemRepository.findByAssetSet_IdOrderByItemOrderAscCreatedAtAsc(assetSetId);
    }

    @Transactional
    public void removeItem(String assetSetId, String itemId) {
        SduiAssetSetItem item = assetSetItemRepository.findByIdAndAssetSet_Id(itemId, assetSetId)
                .orElseThrow(() -> new IllegalArgumentException("asset set item not found"));
        assetSetItemRepository.delete(item);
    }

    @Transactional(readOnly = true)
    public SelectedAssetContext buildSelectedAssetContext(List<String> requestedSetIds) {
        List<String> setIds = requestedSetIds == null ? List.of() : requestedSetIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .limit(MAX_PROMPT_SET_COUNT)
                .toList();
        if (setIds.isEmpty()) {
            return new SelectedAssetContext(List.of(), List.of(), List.of());
        }

        Map<String, SduiAssetSet> setMap = assetSetRepository.findAllById(setIds).stream()
                .collect(Collectors.toMap(SduiAssetSet::getId, s -> s, (a, b) -> a, LinkedHashMap::new));
        if (setMap.isEmpty()) {
            return new SelectedAssetContext(List.of(), List.of(), List.of());
        }

        List<SduiAssetSetItem> allItems = assetSetItemRepository.findByAssetSet_IdInOrderByAssetSet_IdAscItemOrderAscCreatedAtAsc(new ArrayList<>(setMap.keySet()));
        Map<String, List<SduiAssetSetItem>> grouped = allItems.stream().collect(Collectors.groupingBy(i -> i.getAssetSet().getId(), LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> compactSetList = new ArrayList<>();
        List<String> finalSetIds = new ArrayList<>();
        List<SduiAsset> selectedAssets = new ArrayList<>();
        for (Map.Entry<String, SduiAssetSet> entry : setMap.entrySet()) {
            String setId = entry.getKey();
            SduiAssetSet set = entry.getValue();
            List<SduiAssetSetItem> items = grouped.getOrDefault(setId, List.of());
            if (items.isEmpty()) {
                continue;
            }
            finalSetIds.add(setId);
            Map<String, Object> compact = new LinkedHashMap<>();
            compact.put("asset_set_id", set.getId());
            compact.put("name", set.getName());
            compact.put("description", set.getDescription());
            compact.put("tags", set.getTags());
            compact.put("usage_rule", "iterate by ctx.assets with stable ordering; pair resources by usage_type/tags, not by hardcoded names");
            compact.put("runtime_rule", "do not depend on asset names; use cap.asset_search(tags,limit) and processed_payload metadata");

            Map<String, Integer> assetTypeStats = new LinkedHashMap<>();
            Map<String, Integer> processedStatusStats = new LinkedHashMap<>();
            for (SduiAssetSetItem item : items) {
                selectedAssets.add(item.getAsset());
                SduiAsset asset = item.getAsset();
                String type = asset.getAssetType() == null ? "UNKNOWN" : asset.getAssetType();
                String status = asset.getProcessedStatus() == null ? "UNKNOWN" : asset.getProcessedStatus();
                assetTypeStats.put(type, assetTypeStats.getOrDefault(type, 0) + 1);
                processedStatusStats.put(status, processedStatusStats.getOrDefault(status, 0) + 1);
            }
            compact.put("asset_count", items.size());
            compact.put("asset_type_stats", assetTypeStats);
            compact.put("processed_status_stats", processedStatusStats);
            compactSetList.add(compact);
        }
        List<SduiAsset> uniqueAssets = selectedAssets.stream()
                .collect(Collectors.toMap(SduiAsset::getId, a -> a, (a, b) -> a, LinkedHashMap::new))
                .values()
                .stream()
                .toList();
        return new SelectedAssetContext(finalSetIds, compactSetList, uniqueAssets);
    }

    public record SelectedAssetContext(
            List<String> selectedSetIds,
            List<Map<String, Object>> promptSets,
            List<SduiAsset> selectedAssets
    ) {
    }
}
