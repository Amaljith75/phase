"""Separator plugin interface.

A separator turns one audio file into named stem files. The server only
knows this interface, so new engines (BS-RoFormer, MDX-Net, a cloud API...)
can be added by implementing it and registering in ``registry.py``.
"""
from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Dict, List

# progress(fraction 0..1, stage text)
ProgressFn = Callable[[float, str], None]

# Stems the Phase app expects, in its bus order.
PHASE_STEMS = ["vocals", "drums", "bass", "other"]


@dataclass
class ModelInfo:
    id: str
    name: str
    stems: List[str] = field(default_factory=lambda: list(PHASE_STEMS))
    description: str = ""


class Separator(ABC):
    info: ModelInfo

    @abstractmethod
    def available(self) -> bool:
        """True if dependencies are installed so this model can run."""

    @abstractmethod
    def separate(self, audio_path: Path, out_dir: Path, progress: ProgressFn) -> Dict[str, Path]:
        """Separate ``audio_path`` into FLAC files in ``out_dir``.

        Must return at least the four PHASE_STEMS keys.
        """
