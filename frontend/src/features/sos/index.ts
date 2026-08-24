export { default as ProntoSosEntryPage } from './ProntoSosEntryPage';
export { default as ProntoSosScreen } from './ProntoSosScreen';
export type { ProntoSosScreenProps } from './ProntoSosScreen';

export { SosHeader } from './SosHeader';
export type { SosHeaderProps } from './SosHeader';

export { SosScanPanel } from './SosScanPanel';
export type { SosScanPanelProps, SosScanState } from './SosScanPanel';


export { SosCandidateTray } from './SosCandidateTray';
export type { SosCandidateTrayProps } from './SosCandidateTray';

export { SosCandidateCard } from './SosCandidateCard';
export type { SosCandidateCardProps } from './SosCandidateCard';

export { SosSelectedProfessionalPanel } from './SosSelectedProfessionalPanel';
export type { SosSelectedProfessionalPanelProps } from './SosSelectedProfessionalPanel';

export { SosStatusSteps } from './SosStatusSteps';
export type { SosStatusStepsProps } from './SosStatusSteps';

export { toSosUiPhase, SOS_STATUS_COPY, SOS_TRACKING_STEPS, SOS_ERROR_MESSAGES } from './sosUiState';
export type { SosUiPhase, SosStatusCopy } from './sosUiState';

// ---- professional side (MS2) ----

export { default as ProSosPage } from './ProSosPage';

export { SosOfferCard } from './SosOfferCard';
export type { SosOfferCardProps } from './SosOfferCard';

export { SosEtaModal } from './SosEtaModal';
export type { SosEtaModalProps } from './SosEtaModal';

export { SosJobPanel } from './SosJobPanel';
export type { SosJobPanelProps } from './SosJobPanel';

export {
  SOS_OFFER_COPY,
  SOS_JOB_STEPS,
  SOS_PRO_ERROR_MESSAGES,
  SOS_ETA_PRESET_MINUTES,
} from './sosProUiState';
export type { SosOfferCopy, SosJobStep } from './sosProUiState';
export { SosCandidateMarker } from './SosCandidateMarker';
export type { SosCandidateMarkerProps } from './SosCandidateMarker';
export { SosProfessionalSheet } from './SosProfessionalSheet';
export type { SosProfessionalSheetProps } from './SosProfessionalSheet';
export { SosAvatar } from './SosAvatar';
export type { SosAvatarProps } from './SosAvatar';
