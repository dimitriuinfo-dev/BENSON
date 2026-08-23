// BENSON Contact Resolver — data shapes. Pure types only, no device contacts access.

export type PreferredChannel = 'phone' | 'whatsapp' | 'sms' | 'email';

export interface TrustedContact {
  id: string;
  displayName: string;
  aliases?: string[];
  phoneNumbers?: string[];
  emailAddresses?: string[];
  relation?: string;
  preferredChannel?: PreferredChannel;
  isFamily?: boolean;
  isEmergencyContact?: boolean;
}

export interface ContactResolveRequest {
  rawName: string;
  preferredChannel?: PreferredChannel;
  contacts: TrustedContact[];
}

export type ContactResolveStatus = 'resolved' | 'not_found' | 'ambiguous' | 'missing_phone';

export interface ContactResolveResult {
  status: ContactResolveStatus;
  contact?: TrustedContact;
  candidates?: TrustedContact[];
  normalizedQuery: string;
  message: string;
}
