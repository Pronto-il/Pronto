/**
 * Field names match `backend/src/main/java/com/pronto/auth/dto/DefaultAddressRequest.java`
 * exactly (`city`/`street`/`houseNumber` required, the rest optional), so a value of this
 * shape can be sent to the backend as-is without a translation step.
 */
export interface AddressValue {
  city: string;
  street: string;
  houseNumber: string;
  apartment: string;
  floor: string;
  entrance: string;
  addressNotes: string;
}

export const EMPTY_ADDRESS: AddressValue = {
  city: '',
  street: '',
  houseNumber: '',
  apartment: '',
  floor: '',
  entrance: '',
  addressNotes: '',
};
