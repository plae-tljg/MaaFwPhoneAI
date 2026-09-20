# MaaMCP / Everything-Maa authored pipelines

These files are PC-side authoring artifacts imported through the Assistant's
**Import pipeline** picker. `clock_app_maamcp.json` is the first real pass:

- authored against the MaaMCP pipeline protocol (OCR/ROI/DirectHit/StartApp,
  explicit `next`, `on_error` recovery, `post_delay`, `timeout`);
- guided by the vendored Everything-Maa `maa-pipeline-guide` /
  `maa-pipeline-generate` / `maa-pipeline-testing` skill families;
- imported through `Importer.importPipeline`, which now creates a
  `pipelines` + candidate `pipeline_versions` row and keeps the postcondition
  with the version.

## Wrapper format

`Importer` accepts either a plain MaaFW node map:

```json
{ "NodeA": { "recognition": "DirectHit", "action": "StartApp", "package": "..." } }
```

or a thin authoring wrapper:

```json
{
  "entry": "NodeA",
  "pipeline": {
    "NodeA": { "recognition": "DirectHit", "action": "StartApp", "...": "..." }
  },
  "postcondition": {
    "type": "element",
    "recognition": "OCR",
    "expected": ["闹钟", "时钟"],
    "roi": [0, 0, 1280, 720],
    "threshold": 0.3
  }
}
```

The wrapper is not stored inside MaaFW nodes; it lets the PC bench carry a
deterministic postcondition that the on-device Review/Test replay can check
with `RemoteService.recognitionDirect`.

## Device evidence

- `proposal #13` (plain graph import) → Test replay run #44 →
  `verified=1`, OCR hit `闹钟` at box `[388,641,70,40]`.
- `proposal #14` (wrapper import + candidate version #13) → Test replay
  run #52 → `verified=1`, linked to version #13 via
  `pipeline_version_runs`.
