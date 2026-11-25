"""Model registry. Add new separators here."""
from __future__ import annotations

from typing import Dict, List

from .base import Separator
from .demucs_separator import default_demucs_models

_registry: Dict[str, Separator] = {}


def register(sep: Separator) -> None:
    _registry[sep.info.id] = sep


def get(model_id: str) -> Separator | None:
    return _registry.get(model_id)


def all_models() -> List[Separator]:
    return list(_registry.values())


def clear() -> None:
    _registry.clear()


for _m in default_demucs_models():
    register(_m)
