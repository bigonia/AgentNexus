package com.zwbd.agentnexus.sdui.service;

import com.zwbd.agentnexus.file.KnowledgeFileRepository;
import com.zwbd.agentnexus.file.KnowledgeFile;
import com.zwbd.agentnexus.sdui.dto.SduiAssetSourceFileOption;
import com.zwbd.agentnexus.sdui.dto.SduiBindAssetRequest;
import com.zwbd.agentnexus.sdui.dto.SduiRegisterAssetRequest;
import com.zwbd.agentnexus.sdui.model.SduiApp;
import com.zwbd.agentnexus.sdui.model.SduiAsset;
import com.zwbd.agentnexus.sdui.model.SduiAssetBinding;
import com.zwbd.agentnexus.sdui.repo.SduiAppRepository;
import com.zwbd.agentnexus.sdui.repo.SduiAssetBindingRepository;
import com.zwbd.agentnexus.sdui.repo.SduiAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SduiAssetService {

    private final SduiAssetRepository assetRepository;
    private final SduiAssetBindingRepository bindingRepository;
    private final SduiAppRepository appRepository;
    private final KnowledgeFileRepository fileRepository;
    private final SduiAssetProcessorService assetProcessorService;

    @Transactional
    public SduiAsset register(SduiRegisterAssetRequest request) {
        KnowledgeFile file = fileRepository.findById(request.fileId())
                .orElseThrow(() -> new IllegalArgumentException("knowledge file not found"));
        SduiAsset asset = new SduiAsset();
        asset.setFileId(request.fileId());
        asset.setAssetType(request.assetType());
        asset.setName(request.name());
        asset.setTags(request.tags() == null ? new ArrayList<>() : request.tags());
        SduiAssetProcessorService.ProcessingResult processed = assetProcessorService.process(file, request.assetType());
        asset.setProcessedStatus(processed.status());
        asset.setProcessedPayload(processed.payloadJson());
        return assetRepository.save(asset);
    }

    @Transactional(readOnly = true)
    public List<SduiAsset> list(Optional<String> keyword) {
        return keyword.filter(s -> !s.isBlank())
                .map(assetRepository::findByNameContainingIgnoreCase)
                .orElseGet(assetRepository::findAll);
    }

    @Transactional(readOnly = true)
    public List<SduiAssetSourceFileOption> listSourceFiles(Optional<String> keyword) {
        List<KnowledgeFile> files = keyword.filter(s -> !s.isBlank())
                .map(fileRepository::findByOriginalFilenameContainingIgnoreCase)
                .orElseGet(fileRepository::findAll);
        return files.stream()
                .map(file -> new SduiAssetSourceFileOption(
                        file.getId(),
                        file.getOriginalFilename(),
                        file.getSourceSystem(),
                        file.getCreatedAt()
                ))
                .toList();
    }

    @Transactional
    public SduiAssetBinding bindToApp(String appId, SduiBindAssetRequest request) {
        SduiApp app = appRepository.findById(appId).orElseThrow(() -> new IllegalArgumentException("app not found"));
        SduiAsset asset = assetRepository.findById(request.assetId()).orElseThrow(() -> new IllegalArgumentException("asset not found"));

        Optional<SduiAssetBinding> existing = bindingRepository.findByApp_IdAndAsset_IdAndUsageType(appId, request.assetId(), request.usageType());
        if (existing.isPresent()) {
            return existing.get();
        }

        SduiAssetBinding binding = new SduiAssetBinding();
        binding.setApp(app);
        binding.setAsset(asset);
        binding.setUsageType(request.usageType());
        return bindingRepository.save(binding);
    }

    @Transactional(readOnly = true)
    public List<SduiAssetBinding> appAssets(String appId) {
        return bindingRepository.findByApp_Id(appId);
    }

    @Transactional
    public void unbindAsset(String appId, String bindingId) {
        SduiAssetBinding binding = bindingRepository.findByIdAndApp_Id(bindingId, appId)
                .orElseThrow(() -> new IllegalArgumentException("asset binding not found"));
        bindingRepository.delete(binding);
    }
}
