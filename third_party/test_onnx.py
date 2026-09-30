import json
import sys
from pathlib import Path
sys.path.insert(0, 'third_party/indictrans2-python-test')
import numpy as np
import onnxruntime as ort
from tokenizers import Tokenizer

p = Path('app/src/main/assets/models/indictrans2')
src = Tokenizer.from_file(str(p/'tokenizer_src.json'))
tgt = Tokenizer.from_file(str(p/'tokenizer_tgt.json'))
enc = ort.InferenceSession(str(p/'encoder_model.onnx'),providers=['CPUExecutionProvider'])
dec = ort.InferenceSession(str(p/'decoder_model.onnx'),providers=['CPUExecutionProvider'])
past = ort.InferenceSession(str(p/'decoder_with_past_model.onnx'),providers=['CPUExecutionProvider'])
print('model loaded',flush=True)
ids = [i if i < 32322 else 3 for i in src.encode('eng_Latn hin_Deva Evidence was recorded .').ids]
mask = np.ones((1,len(ids)),dtype=np.int64)
hidden = enc.run(['last_hidden_state'],{'input_ids':np.array([ids],dtype=np.int64),'attention_mask':mask})[0]
generated = [2]
previous = None
for step in range(96):
    inputs={'input_ids':np.array([[generated[-1]]],dtype=np.int64),'encoder_attention_mask':mask}
    if step==0:
        inputs['encoder_hidden_states']=hidden
        outputs=dec.run(None,inputs)
    else:
        layers=(len(previous)-1)//4
        for i in range(layers):
            base=1+i*4
            for j,name in enumerate(('decoder.key','decoder.value','encoder.key','encoder.value')):
                inputs[f'past_key_values.{i}.{name}']=previous[base+j]
        outputs=past.run(None,inputs)
    previous=outputs
    token=int(np.argmax(outputs[0][0,-1,:]))
    generated.append(token)
    if token==2: break
print('tokens',generated,flush=True)
print('raw',tgt.decode([i if i<122672 else 3 for i in generated],skip_special_tokens=True),flush=True)
