import { useEffect, useState } from "react";
import type { ChangeEvent, FormEvent } from "react";
import {
  createSettingErrorData,
  INITIAL_SETTING_DATA,
  loadSettingData,
  saveSettingData,
  SAVING_SETTING_STATUS,
  updateSettingField,
} from "./SettingController";
import { ReferenceSetup } from "../ReferenceSetup";
import { ServerStream } from "../ServerStream";
import type { SettingFieldName } from "./type";
import "./SettingList.css";

const SETTINGS_STREAM_CAMERA_ID = 0;

type SettingListProps = {
  selectedCameraId: number | null;
};

export function SettingList({ selectedCameraId }: SettingListProps) {
  const [settingData, setSettingData] = useState(INITIAL_SETTING_DATA);
  const [isReferenceSetupOpen, setIsReferenceSetupOpen] = useState(false);
  const [isServerStreamOpen, setIsServerStreamOpen] = useState(false);

  const isBusy = settingData.status.state === "loading" || settingData.status.state === "saving";
  const brightnessScopeText = selectedCameraId === null ? "All cameras" : `Camera ${selectedCameraId}`;
  const streamCameraId = selectedCameraId ?? SETTINGS_STREAM_CAMERA_ID;

  useEffect(() => {
    let isActive = true;

    loadSettingData(selectedCameraId)
      .catch(createSettingErrorData)
      .then((nextSettingData) => {
        if (!isActive) {
          return;
        }

        setSettingData(nextSettingData);
      });

    return () => {
      isActive = false;
    };
  }, [selectedCameraId]);

  const handleFieldChange = (fieldName: SettingFieldName) => (event: ChangeEvent<HTMLInputElement>) => {
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      form: updateSettingField(currentSettingData.form, fieldName, event.target.value),
    }));
  };

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const formToSave = settingData.form;
    setSettingData((currentSettingData) => ({
      ...currentSettingData,
      status: SAVING_SETTING_STATUS,
    }));

    saveSettingData(formToSave, selectedCameraId)
      .catch((error) => createSettingErrorData(error, formToSave))
      .then(setSettingData);
  };

  return (
    <aside
      className="setting-list"
      aria-label="Настройки"
    >
      <div className="setting-list__header">
        <h2>Настройки</h2>
        <strong data-status={settingData.status.state}>{settingData.status.text}</strong>
      </div>

      <form
        className="setting-list__form"
        onSubmit={handleSubmit}
      >
        <label className="setting-list__field">
          <span>Яркость света</span>
          <strong className="setting-list__scope">{brightnessScopeText}</strong>
          <div className="setting-list__control-row">
            <input
              type="range"
              min="0"
              max="100"
              step="1"
              value={settingData.form.brightnessPercent}
              disabled={isBusy}
              onChange={handleFieldChange("brightnessPercent")}
            />
            <input
              className="setting-list__number"
              type="number"
              min="0"
              max="100"
              step="1"
              value={settingData.form.brightnessPercent}
              disabled={isBusy}
              onChange={handleFieldChange("brightnessPercent")}
            />
          </div>
        </label>

        <label className="setting-list__field">
          <span>Макс. смещение, мм</span>
          <input
            type="number"
            min="0"
            max="100"
            step="0.01"
            value={settingData.form.maxShiftMm}
            disabled={isBusy}
            onChange={handleFieldChange("maxShiftMm")}
          />
        </label>

        <button
          className="setting-list__submit"
          type="submit"
          disabled={isBusy}
        >
          Сохранить
        </button>
        <button
          className="setting-list__submit setting-list__submit--secondary"
          type="button"
          disabled={isBusy}
          onClick={() => setIsReferenceSetupOpen(true)}
        >
          Задать эталон
        </button>
        <button
          className="setting-list__submit setting-list__submit--secondary"
          type="button"
          disabled={isBusy}
          onClick={() => setIsServerStreamOpen(true)}
        >
          Открыть стрим
        </button>
      </form>

      {isReferenceSetupOpen && (
        <ReferenceSetup
          initialJointViewIndex={selectedCameraId}
          onClose={() => setIsReferenceSetupOpen(false)}
        />
      )}
      {isServerStreamOpen && (
        <ServerStream
          isOpen
          cameraId={streamCameraId}
          title={`Camera ${streamCameraId}`}
          onClose={() => setIsServerStreamOpen(false)}
        />
      )}
    </aside>
  );
}
