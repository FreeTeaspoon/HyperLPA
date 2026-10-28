export interface Envelope {
  v: number; id: string; from: string; to: string; pair: string;
  issued: number; expires: number; nonce: string; body: string;
}
export interface Peer {
  id: string; name: string; pair: string; secret: string;
  kind: "phone" | "browser" | "owner"; approved: boolean;
  profiles: boolean; notifications: boolean;
}
export interface Invitation {
  version: number; relay: string; device: string; name: string;
  pair: string; secret: string; expires: number; ticket?: string;
}
export interface PendingOperation { id: string; peer: string; action: string; started: number; readerId: string }
export interface Identity {
  version: 1; relay: string; id: string; token: string; name: string;
  role: "owner" | "controller"; owner: string; peers: Peer[];
  grants: Record<string, Record<string, Peer>>; revision: number;
  invitation?: Invitation; pending: PendingOperation[];
  updated?: number;
}
export interface Reader { id: string; name: string; kind: string; detail?: string; available: boolean; eid?: string; deviceId?: string }
export interface Profile {
  iccid: string; state: "ENABLED" | "DISABLED"; name: string; nickname: string; providerName: string;
  tags?: string[]; isPinned?: boolean; reminderAt?: string; iconBase64?: string;
}
export interface DownloadPreview { profile: Profile; request: Record<string, unknown> }
export interface Snapshot {
  lpa: {
    readers: Reader[]; profiles: Profile[]; selectedReaderId?: string;
    euiccInfo?: { eid: string; productName?: string; freeNonVolatileMemory?: number; defaultSmdpAddress?: string };
    pendingProfileDownload?: DownloadPreview; operation?: { type: string; stage?: string; progress?: number };
    notifications?: { sequenceNumber: number; operation: string; address: string; iccid: string }[];
    discoveredSmdpAddresses?: string[];
  };
  metadata?: Record<string, { tags?: string[]; isPinned?: boolean }>;
  artworkIncluded?: boolean;
}
export interface PhoneNotification { id: string; packageName: string; appLabel: string; title: string; text: string; timestamp: number }
export interface Command { action: string; readerId?: string; eid?: string; iccid?: string; enabled?: boolean; text?: string; confirmed?: boolean; [key: string]: unknown }
export interface Message {
  kind: string; name?: string; requestId?: string; command?: Command; snapshot?: Snapshot;
  groupOwner?: boolean; groupVersion?: number; revision?: number;
  groupSync?: { revision: number; peers: unknown[] };
  confirmed?: boolean; done?: boolean; outcome?: { type: string; failure?: { message?: string; title?: string } };
  phoneNotifications?: PhoneNotification[]; phoneNotificationsAvailable?: boolean; notificationId?: string;
  part?: { transfer: string; index: number; count: number; data: string };
}
export interface Activity { id: string; peer: string; label: string; status: string; time: number; detail?: string }
export interface ViewState {
  loaded: boolean; identity?: Identity; connected: boolean; error?: string;
  online: Record<string, number>; snapshots: Record<string, Snapshot>;
  notifications: Record<string, { available: boolean; entries: PhoneNotification[] }>;
  activities: Activity[]; phoneRevisions: Record<string, number>;
}
