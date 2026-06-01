// GCP regions with their zones
export const GCP_REGIONS = [
  // Americas
  'us-central1',
  'us-east1',
  'us-east4',
  'us-east5',
  'us-west1',
  'us-west2',
  'us-west3',
  'us-west4',
  'us-south1',
  'northamerica-northeast1',
  'northamerica-northeast2',
  'southamerica-east1',
  'southamerica-west1',
  // Europe
  'europe-central2',
  'europe-north1',
  'europe-southwest1',
  'europe-west1',
  'europe-west2',
  'europe-west3',
  'europe-west4',
  'europe-west6',
  'europe-west8',
  'europe-west9',
  'europe-west10',
  'europe-west12',
  // Asia
  'asia-east1',
  'asia-east2',
  'asia-northeast1',
  'asia-northeast2',
  'asia-northeast3',
  'asia-south1',
  'asia-south2',
  'asia-southeast1',
  'asia-southeast2',
  // Middle East / Africa / Australia
  'australia-southeast1',
  'australia-southeast2',
  'me-central1',
  'me-central2',
  'me-west1',
  'africa-south1',
];

// Common GCP zones (most regions have -a, -b, -c)
const ZONE_SUFFIXES = ['a', 'b', 'c'];

export function getZonesForRegion(region) {
  return ZONE_SUFFIXES.map(suffix => `${region}-${suffix}`);
}

export const ALL_GCP_ZONES = GCP_REGIONS.flatMap(r => getZonesForRegion(r));
