"""Generate the module catalog from its reviewed source; no runtime privilege grants."""
import json
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=json.loads((root/'config/permission-catalog.json').read_text())
(root/'src/main/resources/generated/stir/permission-catalog.generated.json').write_text(json.dumps(source,indent=2)+'\n')
