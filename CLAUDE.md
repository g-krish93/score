## Compaction Instructions
When compacting, always preserve:
- Which modules are complete vs in-progress
- Current streaming pipeline state
- Active Play Cricket API decisions
- Any file paths being actively worked on

## Architecture Auto-Update
docs/ARCHITECTURE.md is auto-updated by a Stop hook after every session 
where code files change. You do NOT need to manually update it. The hook 
handles it. However, if you make a major structural change mid-session, 
you can manually trigger an update by saying "update the architecture doc now".
