import {
  FilesetResolver,
  FaceLandmarker,
  ObjectDetector,
} from 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/vision_bundle.mjs';

const WASM = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@0.10.14/wasm';
const FACE_MODEL =
  'https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/1/face_landmarker.task';
const DETECTOR_MODEL =
  'https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/float16/1/efficientdet_lite0.tflite';

let vision;

// GPU быстрее, но есть не везде — при ошибке откатываемся на CPU.
async function create(Task, options) {
  vision ??= FilesetResolver.forVisionTasks(WASM);
  const fileset = await vision;
  const withDelegate = (delegate) => ({ ...options, baseOptions: { ...options.baseOptions, delegate } });
  try {
    return await Task.createFromOptions(fileset, withDelegate('GPU'));
  } catch (err) {
    console.warn('GPU недоступен, используем CPU', err);
    return Task.createFromOptions(fileset, withDelegate('CPU'));
  }
}

export const loadFace = () =>
  create(FaceLandmarker, {
    baseOptions: { modelAssetPath: FACE_MODEL },
    runningMode: 'VIDEO',
    numFaces: 1,
    outputFaceBlendshapes: true, // зевание
    outputFacialTransformationMatrixes: true, // наклон головы
  });

export const loadDetector = () =>
  create(ObjectDetector, {
    baseOptions: { modelAssetPath: DETECTOR_MODEL },
    runningMode: 'VIDEO',
    scoreThreshold: 0.3,
    maxResults: 10,
    categoryAllowlist: ['horse', 'cow', 'sheep', 'dog', 'bear', 'elephant', 'person'],
  });
