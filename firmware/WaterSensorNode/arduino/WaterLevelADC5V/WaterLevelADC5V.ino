#include <Arduino.h>
#include <math.h>
#include <esp_idf_version.h>
#include <sdkconfig.h>
#if !CONFIG_IDF_TARGET_ESP32S3
#error "Select an ESP32-S3 board."
#endif
#if ESP_IDF_VERSION_MAJOR >= 5
#include "esp_adc/adc_cali.h"
#include "esp_adc/adc_cali_scheme.h"
adc_cali_handle_t calHandle = nullptr;
#else
#include "esp_adc_cal.h"
esp_adc_cal_characteristics_t calChars = {};
#endif

// Sensor VCC -> 5V, GND -> GND. OUT -> 10k -> GPIO5 -> 10k -> GND.
// REQUIRED physical divider: NEVER connect a potentially 5V OUT directly to GPIO5.
constexpr uint8_t SENSOR_PIN = 5;
constexpr int SAMPLES = 64;
constexpr float DIVIDER_GAIN = 2.0f; // Two equal 10k resistors; NOT software protection.
bool factoryCalOK = false;

// Mapping guard only; never clamp the reported measured voltage.
// 150..2450mV is an optional conservative window, not the S3 full range.
constexpr bool CONSERVATIVE_WINDOW = false;
constexpr float MAP_MIN_MV = CONSERVATIVE_WINDOW ? 150.0f : 0.0f;
constexpr float MAP_MAX_MV = CONSERVATIVE_WINDOW ? 2450.0f : 2900.0f;

// Optional residual correction AFTER factory calibration.
// Identity placeholders, NOT experimentally measured calibration data.
// Replace ADC_MV with displayed factory mV and REF_MV with DMM mV
// at several stable known voltages. Both arrays must increase strictly.
constexpr bool USE_LUT = false;
const float ADC_MV[] = {150, 500, 1000, 1500, 2000, 2450};
const float REF_MV[] = {150, 500, 1000, 1500, 2000, 2450};
constexpr size_t LUT_N = sizeof(ADC_MV) / sizeof(ADC_MV[0]);
static_assert(sizeof(ADC_MV) == sizeof(REF_MV), "LUT sizes differ");
bool lutOK = false;

float dryMV = NAN, wetMV = NAN;
float drySD = 0, wetSD = 0;
uint32_t previousMs = 0;

bool initFactoryCalibration() {
#if ESP_IDF_VERSION_MAJOR >= 5
  adc_cali_curve_fitting_config_t cfg = {};
  cfg.unit_id = ADC_UNIT_1;
  cfg.atten = ADC_ATTEN_DB_11;
  cfg.bitwidth = ADC_BITWIDTH_12;
  // S3 calibration is not per-channel; leave optional chan at default.
  return adc_cali_create_scheme_curve_fitting(&cfg, &calHandle) == ESP_OK;
#else
  if (esp_adc_cal_check_efuse(ESP_ADC_CAL_VAL_EFUSE_TP_FIT) != ESP_OK)
    return false;
  return esp_adc_cal_characterize(ADC_UNIT_1, ADC_ATTEN_DB_11,
           ADC_WIDTH_BIT_12, 1100, &calChars) == ESP_ADC_CAL_VAL_EFUSE_TP_FIT;
#endif
}

float factoryMillivolts(uint16_t raw) {
  if (!factoryCalOK) return NAN;
#if ESP_IDF_VERSION_MAJOR >= 5
  int mv = 0;
  if (adc_cali_raw_to_voltage(calHandle, raw, &mv) != ESP_OK) return NAN;
  return float(mv);
#else
  return float(esp_adc_cal_raw_to_voltage(raw, &calChars));
#endif
}

float correctWithLUT(float mv) {
  if (!USE_LUT) return mv;
  if (!lutOK || !isfinite(mv) || mv < ADC_MV[0] || mv > ADC_MV[LUT_N-1])
    return NAN; // No extrapolation outside measured calibration points.
  for (size_t i = 1; i < LUT_N; ++i) {
    if (mv <= ADC_MV[i]) {
      float t = (mv-ADC_MV[i-1]) / (ADC_MV[i]-ADC_MV[i-1]);
      return REF_MV[i-1] + t*(REF_MV[i]-REF_MV[i-1]);
    }
  }
  return NAN;
}

void setup() {
  Serial.begin(115200);
  delay(1500); // Do not wait forever for a serial monitor.
  pinMode(SENSOR_PIN, INPUT);
  analogReadResolution(12); // 0..4095, NOT MicroPython's 0..65535.
  analogSetAttenuation(ADC_11db);
  analogSetPinAttenuation(SENSOR_PIN, ADC_11db);
  analogRead(SENSOR_PIN); // Initialize the Arduino ADC channel.
  factoryCalOK = initFactoryCalibration();
  lutOK = LUT_N >= 2;
  for (size_t i = 1; i < LUT_N; ++i)
    lutOK = lutOK && ADC_MV[i] > ADC_MV[i-1] && REF_MV[i] > REF_MV[i-1];
  Serial.printf("# GPIO5 ADC1_CH4; factory_cal=%s; LUT=%s\n",
                factoryCalOK ? "OK" : "FAILED",
                USE_LUT ? (lutOK ? "ON" : "INVALID") : "OFF");
  Serial.println("# Hold dry, send D; hold reference wet depth, send W.");
  Serial.println("# Endpoints are RAM-only; repeat after reset. Send D again to restart calibration.");
  Serial.println("raw12_avg,raw_min,raw_max,efuse_mV,adc_corrected_mV,sensor_estimated_mV,sd_mV,level_pct,status");
}

void loop() {
  if (uint32_t(millis()-previousMs) < 1000) { delay(1); return; }
  previousMs = millis();
  uint32_t sumRaw = 0;
  uint16_t rawMin = 4095, rawMax = 0;
  double sumFactory = 0, sumMV = 0, sumSquare = 0;
  bool factoryReadOK = factoryCalOK;
  bool conversionsOK = factoryCalOK;
  for (int i = 0; i < SAMPLES; ++i) {
    uint16_t raw = analogRead(SENSOR_PIN);
    sumRaw += raw;
    if (raw < rawMin) rawMin = raw;
    if (raw > rawMax) rawMax = raw;
    // Calibrate the SAME raw sample, avoiding a second ADC conversion.
    float factoryMV = factoryMillivolts(raw);
    float mv = correctWithLUT(factoryMV);
    if (!isfinite(factoryMV)) factoryReadOK = false;
    else sumFactory += factoryMV;
    if (!isfinite(factoryMV) || !isfinite(mv)) conversionsOK = false;
    else { sumMV += mv; sumSquare += double(mv)*mv; }
    delay(2);
  }
  float rawAvg = float(sumRaw)/SAMPLES;
  float efuseMV = factoryReadOK ? sumFactory/SAMPLES : NAN;
  float mv = conversionsOK ? sumMV/SAMPLES : NAN;
  double variance = conversionsOK ? sumSquare/SAMPLES-double(mv)*mv : 0;
  float sd = sqrt(variance > 0 ? variance : 0);
  bool inWindow = isfinite(mv) && mv >= MAP_MIN_MV && mv <= MAP_MAX_MV;
  bool saturated = rawMax >= 4095;

  while (Serial.available()) {
    char c = Serial.read();
    if (c >= 'a' && c <= 'z') c -= 'a'-'A';
    if (c != 'D' && c != 'W') continue;
    if (!inWindow || saturated) { Serial.println("# Endpoint rejected: range/calibration"); continue; }
    if (c == 'D') { dryMV = mv; drySD = sd; wetMV = NAN; }
    else { wetMV = mv; wetSD = sd; }
    Serial.printf("# %c endpoint=%.2fmV, sd=%.2fmV\n", c, mv, sd);
  }

  float percent = NAN;
  const char *status = "NEED_D_AND_W";
  // Experimental discrimination rule, not a sensor accuracy specification.
  float noiseLimit = 3*(drySD+wetSD);
  float requiredSpan = noiseLimit > 50 ? noiseLimit : 50;
  if (!conversionsOK) status = "CAL_OR_LUT_ERROR";
  else if (saturated) status = "ADC_SATURATED";
  else if (!inWindow) status = "OUTSIDE_MAP_WINDOW";
  else if (isfinite(dryMV) && isfinite(wetMV)) {
    if (fabsf(wetMV-dryMV) < requiredSpan) status = "WEAK_SIGNAL";
    else {
      percent = 100*(mv-dryMV)/(wetMV-dryMV); // Also supports reverse response.
      percent = constrain(percent, 0.0f, 100.0f);
      status = "RELATIVE_LEVEL";
    }
  }
  Serial.printf("%.2f,%u,%u,%.2f,%.2f,%.2f,%.2f,",rawAvg,rawMin,rawMax,efuseMV,mv,mv*DIVIDER_GAIN,sd);
  if (isfinite(percent)) Serial.print(percent,1); else Serial.print("NA");
  Serial.printf(",%s\n",status);
}
