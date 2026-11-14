const viewer = document.getElementById('viewer3d');

if (viewer) {
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)');
  const applyMotion = () => {
    viewer.toggleAttribute('auto-rotate', !reduceMotion.matches);
  };
  applyMotion();
  reduceMotion.addEventListener('change', applyMotion);

  const showFallback = () => {
    const img = document.createElement('img');
    img.className = 'viewer-fallback';
    img.src = 'assets/shots/shot-1.webp';
    img.width = 540;
    img.height = 1080;
    img.alt = 'The myfrnd dashboard with the 3D companion';
    viewer.replaceWith(img);
    const caption = img.parentElement.querySelector('figcaption');
    if (caption) caption.textContent = 'Your browser cannot show 3D here, so this is a picture from the app.';
  };

  const canvas = document.createElement('canvas');
  const webgl = canvas.getContext('webgl2') || canvas.getContext('webgl');
  if (!webgl) {
    showFallback();
  } else {
    viewer.addEventListener('load', () => viewer.setAttribute('data-loaded', ''));
    viewer.addEventListener('error', showFallback);
  }
}
