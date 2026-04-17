// @ts-check
const canvas = document.querySelector('canvas');
const ctx = canvas.getContext('2d');
const textInput = document.querySelector('textarea');
const defaultText = textInput.value;
let inverted = false;
let bgColor = 'black';
let textColor = 'white';

const commonInstance = new NfcEIWCommon(canvas);
const clearCanvas = commonInstance.clearCanvas.bind(commonInstance);

/**
 * Calculates the largest font size that will allow the text to fit.
 * @param {string[]} lines An array of text lines.
 * @param {string} fontFace The font to use.
 * @param {number} width The available width.
 * @param {number} height The available height.
 */
function getFontSizeToFit(lines, fontFace, width, height) {
	const lineSpacingPercent = 20; // 20% extra space between lines
	ctx.font = `1px ${fontFace}`; // Start with a tiny font to measure

	let fitFontWidth = Number.MAX_VALUE;
    if (lines && lines.length > 0) {
		lines.forEach((line) => {
            if (line.trim()) { // Only measure non-empty lines
			    fitFontWidth = Math.min(fitFontWidth, width / ctx.measureText(line).width);
            }
		});
	} else {
        return 0; // No text, no font size
    }

	let fitFontHeight = height / (lines.length * (1 + (lineSpacingPercent / 100)));
	return Math.min(fitFontHeight, fitFontWidth);
}

function setInverted(updatedInverted) {
	inverted = typeof updatedInverted === 'boolean' ? updatedInverted : !inverted;
	if (inverted) {
		bgColor = 'black';
		textColor = 'white';
	} else {
		bgColor = 'white';
		textColor = 'black';
	}
	renderToCanvas();
}

function drawBg() {
	ctx.fillStyle = bgColor;
	ctx.fillRect(0, 0, canvas.width, canvas.height);
}

function renderToCanvas() {
	clearCanvas();
	drawBg();

    const text = textInput.value;
    if (!text) return;

    // --- START: New Auto-Sizing Logic ---
    const fontFace = 'monospace';
    const padding = 10;
    const availableWidth = canvas.width - (padding * 2);
    const availableHeight = canvas.height - (padding * 2);

    // Split the text only by its original line breaks
    const lines = text.replace(/\r?\n/g, '\n').split('\n');

    // Calculate the best font size to fit the lines
    const fontSize = getFontSizeToFit(lines, fontFace, availableWidth, availableHeight);

    // Set the final font and draw the text
    ctx.font = `${fontSize}px ${fontFace}`;
    ctx.fillStyle = textColor;
    ctx.textAlign = 'left';
    ctx.textBaseline = 'top';

    const lineHeight = fontSize * 1.2;
    let y = padding;
    for (const line of lines) {
        ctx.fillText(line, padding, y);
        y += lineHeight;
    }
    // --- END: New Auto-Sizing Logic ---
}

renderToCanvas();
setTimeout(renderToCanvas, 200);

// Attach listeners
textInput.addEventListener('keyup', renderToCanvas);
textInput.addEventListener('input', renderToCanvas);
document.querySelector('button#addLineBreak').addEventListener('click', () => {
	textInput.value += '\n';
    renderToCanvas();
});
document.querySelector('button#reset').addEventListener('click', () => {
	textInput.value = defaultText;
	setInverted(false);
	renderToCanvas();
});
document.querySelector('button#setInverted').addEventListener('click', () => {
	setInverted();
});