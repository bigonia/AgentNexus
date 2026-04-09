package com.zwbd.agentnexus.sdui;

import com.zwbd.agentnexus.common.web.ApiResponse;
import com.zwbd.agentnexus.sdui.dto.SduiDeviceControlRequest;
import com.zwbd.agentnexus.sdui.dto.SduiBindAssetRequest;
import com.zwbd.agentnexus.sdui.dto.SduiClaimDeviceRequest;
import com.zwbd.agentnexus.sdui.dto.SduiControlDispatchResult;
import com.zwbd.agentnexus.sdui.dto.SduiGenerateAppRequest;
import com.zwbd.agentnexus.sdui.dto.SduiGeneratedAppResponse;
import com.zwbd.agentnexus.sdui.dto.SduiAssetSourceFileOption;
import com.zwbd.agentnexus.sdui.dto.SduiAddAssetSetItemsRequest;
import com.zwbd.agentnexus.sdui.dto.SduiCreateAssetSetRequest;
import com.zwbd.agentnexus.sdui.dto.SduiPublishRequest;
import com.zwbd.agentnexus.sdui.dto.SduiRegisterAssetRequest;
import com.zwbd.agentnexus.sdui.dto.SduiReviseAppRequest;
import com.zwbd.agentnexus.sdui.dto.SduiRuntimeWorkerStatus;
import com.zwbd.agentnexus.sdui.model.SduiApp;
import com.zwbd.agentnexus.sdui.model.SduiAppVersion;
import com.zwbd.agentnexus.sdui.model.SduiAsset;
import com.zwbd.agentnexus.sdui.model.SduiAssetBinding;
import com.zwbd.agentnexus.sdui.model.SduiAssetSet;
import com.zwbd.agentnexus.sdui.model.SduiAssetSetItem;
import com.zwbd.agentnexus.sdui.model.SduiDevice;
import com.zwbd.agentnexus.sdui.model.SduiDeviceTelemetry;
import com.zwbd.agentnexus.sdui.service.SduiAppRuntimeService;
import com.zwbd.agentnexus.sdui.service.SduiAppService;
import com.zwbd.agentnexus.sdui.service.SduiAssetService;
import com.zwbd.agentnexus.sdui.service.SduiAssetSetService;
import com.zwbd.agentnexus.sdui.service.SduiDeviceService;
import com.zwbd.agentnexus.sdui.service.SduiOpsService;
import com.zwbd.agentnexus.sdui.service.SduiScriptRuntimeManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.DeleteMapping;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sdui")
@RequiredArgsConstructor
public class SduiManagementController {

    private final SduiAppService appService;
    private final SduiAppRuntimeService runtimeService;
    private final SduiDeviceService deviceService;
    private final SduiAssetService assetService;
    private final SduiAssetSetService assetSetService;
    private final SduiScriptRuntimeManager scriptRuntimeManager;
    private final SduiOpsService opsService;

    @PostMapping("/apps/generate")
    public ApiResponse<SduiGeneratedAppResponse> generate(@Valid @RequestBody SduiGenerateAppRequest request) {
        return ApiResponse.ok(appService.generate(request));
    }

    @PostMapping("/apps/{appId}/revise")
    public ApiResponse<SduiGeneratedAppResponse> revise(@PathVariable String appId, @Valid @RequestBody SduiReviseAppRequest request) {
        return ApiResponse.ok(appService.revise(appId, request.instruction()));
    }

    @GetMapping("/apps")
    public ApiResponse<List<SduiApp>> apps() {
        return ApiResponse.ok(appService.listApps());
    }

    @GetMapping("/apps/{appId}/versions")
    public ApiResponse<List<SduiAppVersion>> versions(@PathVariable String appId) {
        return ApiResponse.ok(appService.listVersions(appId));
    }

    @PostMapping("/apps/{appId}/publish")
    public ApiResponse<Map<String, Object>> publish(@PathVariable String appId, @Valid @RequestBody SduiPublishRequest request) {
        int sent = runtimeService.publish(appId, request);
        return ApiResponse.ok(Map.of("sent", sent, "requested", request.deviceIds().size()));
    }

    @GetMapping("/devices")
    public ApiResponse<List<SduiDevice>> devices() {
        return ApiResponse.ok(deviceService.listDevices());
    }

    @GetMapping("/devices/unclaimed")
    public ApiResponse<List<SduiDevice>> unclaimedDevices() {
        return ApiResponse.ok(deviceService.listUnclaimedDevices());
    }

    @PostMapping("/devices/{deviceId}/claim")
    public ApiResponse<SduiDevice> claimDevice(@PathVariable String deviceId, @Valid @RequestBody SduiClaimDeviceRequest request) {
        return ApiResponse.ok(deviceService.claimDevice(deviceId, request.claimCode(), request.deviceName()));
    }

    @GetMapping("/devices/{deviceId}/telemetry")
    public ApiResponse<List<SduiDeviceTelemetry>> telemetry(@PathVariable String deviceId) {
        return ApiResponse.ok(deviceService.telemetry(deviceId));
    }

    @PostMapping("/devices/{deviceId}/control")
    public ApiResponse<Map<String, Object>> control(@PathVariable String deviceId, @Valid @RequestBody SduiDeviceControlRequest request) {
        SduiControlDispatchResult result = deviceService.controlDevice(deviceId, request);
        return ApiResponse.ok(Map.of(
                "sent", result.sent(),
                "deviceId", deviceId,
                "command", result.action(),
                "cmdId", result.cmdId(),
                "requestedValue", result.requestedValue(),
                "status", result.status()
        ));
    }

    @GetMapping("/runtime/workers")
    public ApiResponse<List<SduiRuntimeWorkerStatus>> workers() {
        return ApiResponse.ok(scriptRuntimeManager.workerStatuses());
    }

    @GetMapping("/ops/overview")
    public ApiResponse<Map<String, Object>> opsOverview() {
        return ApiResponse.ok(opsService.overview());
    }

    @PostMapping("/assets")
    public ApiResponse<SduiAsset> registerAsset(@Valid @RequestBody SduiRegisterAssetRequest request) {
        return ApiResponse.ok(assetService.register(request));
    }

    @GetMapping("/assets/source-files")
    public ApiResponse<List<SduiAssetSourceFileOption>> sourceFiles(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(assetService.listSourceFiles(java.util.Optional.ofNullable(keyword)));
    }

    @GetMapping("/assets")
    public ApiResponse<List<SduiAsset>> assets(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(assetService.list(java.util.Optional.ofNullable(keyword)));
    }

    @PostMapping("/asset-sets")
    public ApiResponse<SduiAssetSet> createAssetSet(@Valid @RequestBody SduiCreateAssetSetRequest request) {
        return ApiResponse.ok(assetSetService.create(request));
    }

    @GetMapping("/asset-sets")
    public ApiResponse<List<SduiAssetSet>> assetSets(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(assetSetService.list(java.util.Optional.ofNullable(keyword)));
    }

    @PostMapping("/asset-sets/{assetSetId}/items")
    public ApiResponse<List<SduiAssetSetItem>> addAssetSetItems(@PathVariable String assetSetId,
                                                                 @Valid @RequestBody SduiAddAssetSetItemsRequest request) {
        return ApiResponse.ok(assetSetService.addAssets(assetSetId, request));
    }

    @GetMapping("/asset-sets/{assetSetId}/items")
    public ApiResponse<List<SduiAssetSetItem>> assetSetItems(@PathVariable String assetSetId) {
        return ApiResponse.ok(assetSetService.items(assetSetId));
    }

    @DeleteMapping("/asset-sets/{assetSetId}/items/{itemId}")
    public ApiResponse<Map<String, Object>> removeAssetSetItem(@PathVariable String assetSetId, @PathVariable String itemId) {
        assetSetService.removeItem(assetSetId, itemId);
        return ApiResponse.ok(Map.of("assetSetId", assetSetId, "itemId", itemId, "deleted", true));
    }

    @PostMapping("/apps/{appId}/assets:bind")
    public ApiResponse<SduiAssetBinding> bindAsset(@PathVariable String appId, @Valid @RequestBody SduiBindAssetRequest request) {
        return ApiResponse.ok(assetService.bindToApp(appId, request));
    }

    @GetMapping("/apps/{appId}/assets")
    public ApiResponse<List<SduiAssetBinding>> appAssets(@PathVariable String appId) {
        return ApiResponse.ok(assetService.appAssets(appId));
    }

    @DeleteMapping("/apps/{appId}/assets/{bindingId}")
    public ApiResponse<Map<String, Object>> unbindAsset(@PathVariable String appId, @PathVariable String bindingId) {
        assetService.unbindAsset(appId, bindingId);
        return ApiResponse.ok(Map.of("appId", appId, "bindingId", bindingId, "deleted", true));
    }
}
