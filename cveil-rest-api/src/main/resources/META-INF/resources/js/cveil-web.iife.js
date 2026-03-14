var CveilWeb=(function(y){"use strict";var ct=y=>{throw TypeError(y)};var lt=(y,a,b)=>a.has(y)||ct("Cannot "+b);var T=(y,a,b)=>(lt(y,a,"read from private field"),b?b.call(y):a.get(y)),dt=(y,a,b)=>a.has(y)?ct("Cannot add the same private member more than once"):a instanceof WeakSet?a.add(y):a.set(y,b),ut=(y,a,b,k)=>(lt(y,a,"write to private field"),k?k.call(y,b):a.set(y,b),b);var E;function a(e){const t=document.createElement("div");return t.textContent=e,t.innerHTML}function b(e){document.querySelectorAll('[id^="section-"]').forEach(n=>n.classList.add("hidden")),document.querySelectorAll(".tab-btn").forEach(n=>{n.classList.remove("border-cv-500","text-cv-500"),n.classList.add("border-transparent","text-gray-400")}),document.getElementById("section-"+e).classList.remove("hidden");const t=document.getElementById("tab-"+e);t.classList.add("border-cv-500","text-cv-500"),t.classList.remove("border-transparent","text-gray-400"),window.location.hash=e}function k(e){return async function(n,s={}){const o={...s.headers};s.body&&(o["Content-Type"]="application/json");const r=await fetch(e+n,{...s,headers:o});if(!r.ok){const c=await r.json().catch(()=>({message:r.statusText}));throw new Error(c.message||r.statusText)}return r.status===204?null:r.json()}}let se=null;function C(e,t){document.getElementById("delete-modal-msg").textContent=e,document.getElementById("delete-modal").classList.remove("hidden"),se=t}function oe(){document.getElementById("delete-modal").classList.add("hidden"),se=null}function ge(){document.getElementById("delete-modal-confirm").addEventListener("click",async()=>{se&&(await se(),oe())})}function me(){const e=document.getElementById("panel-overlay"),t=document.getElementById("panel");e.classList.remove("hidden"),requestAnimationFrame(()=>t.classList.remove("translate-x-full"))}function h(){document.getElementById("panel").classList.add("translate-x-full"),setTimeout(()=>document.getElementById("panel-overlay").classList.add("hidden"),300)}function q(e,t="gray"){const n={gray:"bg-gray-700 text-gray-300",teal:"bg-cv-900/50 text-cv-500 border border-cv-500/30",blue:"bg-blue-900/50 text-blue-400 border border-blue-500/30",purple:"bg-purple-900/50 text-purple-400 border border-purple-500/30"};return`<span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${n[t]||n.gray}">${a(e)}</span>`}function j(e,t,n=!1){e.textContent=t,e.className="text-sm mt-3 "+(n?"text-red-400":"text-green-400"),e.classList.remove("hidden"),setTimeout(()=>e.classList.add("hidden"),4e3)}function z(e){return e?new Date(e).toLocaleString(void 0,{month:"short",day:"numeric",hour:"2-digit",minute:"2-digit",second:"2-digit"}):"—"}function ye(e){document.readyState!=="loading"?e():document.addEventListener("DOMContentLoaded",e)}const N=k("/api/v1/catalogue");let K=[],Y=[],ae=[];function fe(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");({"entity-add":()=>{n.textContent="Add Entity",s.innerHTML=Ce()},"entity-edit":()=>{n.textContent="Edit Entity",s.innerHTML=Ce(t)},"field-add":()=>{n.textContent="Add Field",s.innerHTML=ft()},"field-categories":()=>{n.textContent="Manage Categories",s.innerHTML=ht(t)},"category-add":()=>{n.textContent="Add Category",s.innerHTML=Oe()},"category-edit":()=>{n.textContent="Edit Category",s.innerHTML=Oe(t)}}[e]||(()=>{s.innerHTML=""}))(),me()}async function re(){try{K=await N("/entities"),pt()}catch(e){document.getElementById("entities-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${a(e.message)}</td></tr>`}}function pt(){const e=document.getElementById("entities-table");if(!K.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No entities defined yet.</td></tr>';return}e.innerHTML=K.map(t=>`
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${a(t.entityName)}</td>
            <td class="px-6 py-4 text-gray-400">${a(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("entity-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteEntity("${a(t.entityName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Ce(e){const t=e?a(e.entityName):"",n=e?a(e.description||""):"";return`
        <form onsubmit="saveEntity(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-ent-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-ent-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Create"}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-ent-msg" class="hidden"></span>
        </form>`}async function gt(e,t,n){e.preventDefault();const s=document.getElementById("pf-ent-name").value.trim(),o=document.getElementById("pf-ent-desc").value.trim(),r=document.getElementById("pf-ent-msg");try{t?await N(`/entities/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:o})}):await N("/entities",{method:"POST",body:JSON.stringify({entityName:s,description:o})}),h(),re()}catch(c){j(r,c.message,!0)}}function mt(e){C(`Delete entity "${e}"? This cannot be undone. Entities with fields must have their fields removed first.`,async()=>{try{await N(`/entities/${encodeURIComponent(e)}`,{method:"DELETE"}),re()}catch(t){alert(t.message)}})}async function V(){try{Y=await N("/fields"),yt()}catch(e){document.getElementById("fields-table").innerHTML=`<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${a(e.message)}</td></tr>`}}function yt(){const e=document.getElementById("fields-table");if(!Y.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No fields defined yet.</td></tr>';return}e.innerHTML=Y.map(t=>`
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${a(t.fieldName)}</td>
            <td class="px-6 py-4 text-gray-400">${t.entity?a(t.entity.entityName):'<span class="text-gray-600">—</span>'}</td>
            <td class="px-6 py-4 text-gray-400">${a(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.categories||[]).map(n=>q(n.categoryName,"purple")).join("")}
                    <button onclick='openPanel("field-categories", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-600 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteField("${t.entity?a(t.entity.entityName):""}", "${a(t.fieldName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function ft(){return`
        <form onsubmit="saveField(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Field Name</label>
                    <input id="pf-fld-name" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Entity</label>
                    <select id="pf-fld-entity" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select entity…</option>
                        ${K.map(t=>`<option value="${a(t.entityName)}">${a(t.entityName)}</option>`).join("")}
                    </select>
                    ${K.length?"":'<p class="text-xs text-amber-400 mt-1">No entities registered. Add an entity first.</p>'}
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-fld-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Entity Description <span class="text-gray-600">(optional, used if entity is new)</span></label>
                    <textarea id="pf-fld-ent-desc" rows="2"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">Create</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-fld-msg" class="hidden"></span>
        </form>`}async function xt(e){e.preventDefault();const t=document.getElementById("pf-fld-name").value.trim(),n=document.getElementById("pf-fld-entity").value,s=document.getElementById("pf-fld-desc").value.trim()||null,o=document.getElementById("pf-fld-ent-desc").value.trim()||null,r=document.getElementById("pf-fld-msg");try{await N("/fields",{method:"POST",body:JSON.stringify({fieldName:t,entityName:n,description:s,entityDescription:o})}),h(),V(),re()}catch(c){j(r,c.message,!0)}}function bt(e,t){C(`Delete field "${t}" from entity "${e}"? This cannot be undone.`,async()=>{try{await N(`/fields/${encodeURIComponent(e)}/${encodeURIComponent(t)}`,{method:"DELETE"}),V()}catch(n){alert(n.message)}})}async function xe(){try{ae=await N("/categories"),Et()}catch(e){document.getElementById("categories-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${a(e.message)}</td></tr>`}}function ht(e){const t=e.entity?e.entity.entityName:"",n=new Set((e.categories||[]).map(o=>o.categoryName)),s=ae.filter(o=>!n.has(o.categoryName)).map(o=>`<option value="${a(o.categoryName)}">${a(o.categoryName)}</option>`).join("");return`
        <p class="text-sm text-gray-400 mb-4">Manage categories for field <strong class="text-white">${a(e.fieldName)}</strong> on entity <strong class="text-white">${a(t)}</strong></p>
        <div id="fc-list" class="space-y-2 mb-6">
            ${(e.categories||[]).map(o=>`
                <div class="flex items-center justify-between bg-gray-800/60 border border-gray-700 rounded-lg px-3 py-2">
                    <span class="text-sm text-white">${a(o.categoryName)}</span>
                    <button onclick="unassignFieldCategory('${a(t)}','${a(e.fieldName)}','${a(o.categoryName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No categories assigned.</p>'}
        </div>
        <hr class="border-gray-700 mb-4">
        <p class="text-sm text-gray-400 mb-3">Assign a category</p>
        <form onsubmit="assignFieldCategory(event, '${a(t)}', '${a(e.fieldName)}')">
            <div class="flex gap-2">
                <select id="fc-select" class="flex-1 bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                    ${s}
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Assign</button>
            </div>
            <span id="fc-msg" class="hidden"></span>
        </form>`}async function vt(e,t,n){e.preventDefault();const s=document.getElementById("fc-select").value;if(s)try{await N("/fields/categories",{method:"POST",body:JSON.stringify({fieldName:n,entityName:t,categoryName:s})}),h(),await V();const o=Y.find(r=>r.fieldName===n&&r.entity&&r.entity.entityName===t);o&&fe("field-categories",o)}catch(o){j(document.getElementById("fc-msg"),o.message,!0)}}async function wt(e,t,n){try{await N(`/fields/${encodeURIComponent(e)}/${encodeURIComponent(t)}/categories/${encodeURIComponent(n)}`,{method:"DELETE"}),h(),await V();const s=Y.find(o=>o.fieldName===t&&o.entity&&o.entity.entityName===e);s&&fe("field-categories",s)}catch(s){alert(s.message)}}function Et(){const e=document.getElementById("categories-table");if(!ae.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No categories defined yet.</td></tr>';return}e.innerHTML=ae.map(t=>`
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${a(t.categoryName)}</td>
            <td class="px-6 py-4 text-gray-400">${a(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("category-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteCategory("${a(t.categoryName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Oe(e){const t=e?a(e.categoryName):"",n=e?a(e.description||""):"";return`
        <form onsubmit="saveCategory(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-cat-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-cat-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Create"}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-cat-msg" class="hidden"></span>
        </form>`}async function Nt(e,t,n){e.preventDefault();const s=document.getElementById("pf-cat-name").value.trim(),o=document.getElementById("pf-cat-desc").value.trim(),r=document.getElementById("pf-cat-msg");try{t?await N(`/categories/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:o})}):await N("/categories",{method:"POST",body:JSON.stringify({categoryName:s,description:o})}),h(),xe()}catch(c){j(r,c.message,!0)}}function $t(e){C(`Delete category "${e}"? This cannot be undone. Categories still assigned to fields must be unassigned first.`,async()=>{try{await N(`/categories/${encodeURIComponent(e)}`,{method:"DELETE"}),xe()}catch(t){alert(t.message)}})}function St(){Object.assign(window,{showTab:b,openPanel:fe,closePanel:h,closeDeleteModal:oe,saveEntity:gt,deleteEntity:mt,saveField:xt,deleteField:bt,assignFieldCategory:vt,unassignFieldCategory:wt,saveCategory:Nt,deleteCategory:$t}),ge(),ye(()=>{re(),V(),xe();const e=window.location.hash.replace("#","");["entities","fields","categories"].includes(e)&&b(e)})}const Tt=Object.freeze(Object.defineProperty({__proto__:null,init:St},Symbol.toStringTag,{value:"Module"})),I=Object.freeze({Plaintext:"Plaintext",RedactAll:"RedactAll",RedactUpToLast4:"RedactUpToLast4",Hash256:"Hash256",RedactLocal:"RedactLocal"}),jt=Object.values(I);function ie(e){return typeof e=="string"?"***":typeof e=="number"?(Number.isInteger(e),0):typeof e=="boolean"?!1:null}function It(e){if(typeof e=="string"){const t=[...e];if(t.length<=4)return"****";const n=t.length-4;return"*".repeat(n)+t.slice(n).join("")}return ie(e)}async function Lt(e){if(typeof e=="string"||typeof e=="number"){const t=String(e),n=new TextEncoder().encode(t),s=await crypto.subtle.digest("SHA-256",n);return Array.from(new Uint8Array(s)).map(o=>o.toString(16).padStart(2,"0")).join("")}return ie(e)}function Bt(e){if(typeof e=="string"){const t=e.indexOf("@");if(t>=0){const n=e.slice(t),s=Math.max(t,4);return"*".repeat(s)+n}}return ie(e)}async function Me(e,t){switch(t){case I.Plaintext:return e;case I.RedactAll:return ie(e);case I.RedactUpToLast4:return It(e);case I.Hash256:return Lt(e);case I.RedactLocal:return Bt(e);default:return e}}async function kt(e,t){return be(structuredClone(e),"",t)}async function be(e,t,n){if(e!==null&&typeof e=="object"&&!Array.isArray(e))for(const s of Object.keys(e)){const o=t?`${t}.${s}`:s,r=n[o];if(r&&r!==I.Plaintext){e[s]=await Me(e[s],r);continue}e[s]=await be(e[s],o,n)}else if(Array.isArray(e))for(let s=0;s<e.length;s++)e[s]=await be(e[s],t,n);else if(t){const s=n[t];if(s&&s!==I.Plaintext)return Me(e,s)}return e}function Ae(e,t=""){const n=[];if(e!==null&&typeof e=="object"&&!Array.isArray(e))for(const[s,o]of Object.entries(e)){const r=t?`${t}.${s}`:s;o!==null&&typeof o=="object"&&!Array.isArray(o)?n.push(...Ae(o,r)):n.push(r)}return n}const Ct=new TextEncoder,Ot=new TextDecoder;class X extends Error{constructor(t){super(t),this.name="CveilWasmError"}}const ke=class ke{constructor(){dt(this,E,null)}static async load(t){const n=new ke,s={env:{cveil_host_time_ms:()=>BigInt(Date.now())}};let o;if(typeof WebAssembly.instantiateStreaming=="function")o=(await WebAssembly.instantiateStreaming(fetch(t),s)).instance.exports;else{const c=await(await fetch(t)).arrayBuffer(),l=await WebAssembly.compile(c);o=(await WebAssembly.instantiate(l,s)).exports}return ut(n,E,o),n}get exports(){return T(this,E)}writeString(t){const n=Ct.encode(t),s=T(this,E).cveil_alloc(n.length);return new Uint8Array(T(this,E).memory.buffer,s,n.length).set(n),[s,n.length]}readResult(){const t=T(this,E).cveil_result_ptr(),n=T(this,E).cveil_result_len();if(!t||!n)return"";const s=new Uint8Array(T(this,E).memory.buffer,t,n);return Ot.decode(s)}freeString(t,n){T(this,E).cveil_dealloc(t,n)}call0(t){const n=t(),s=this.readResult();if(T(this,E).cveil_free_result(),n!==0)throw new X(s||"WASM call failed");return s}call1(t,n){const[s,o]=this.writeString(n);try{const r=t(s,o),c=this.readResult();if(T(this,E).cveil_free_result(),r!==0)throw new X(c||"WASM call failed");return c}finally{this.freeString(s,o)}}call2(t,n,s){const[o,r]=this.writeString(n),[c,l]=this.writeString(s);try{const g=t(o,r,c,l),m=this.readResult();if(T(this,E).cveil_free_result(),g!==0)throw new X(m||"WASM call failed");return m}finally{this.freeString(o,r),this.freeString(c,l)}}call4(t,n,s,o,r){const[c,l]=this.writeString(n),[g,m]=this.writeString(s),[f,u]=this.writeString(o),[i,p]=this.writeString(r);try{const x=t(c,l,g,m,f,u,i,p),d=this.readResult();if(T(this,E).cveil_free_result(),x!==0)throw new X(d||"WASM call failed");return d}finally{this.freeString(c,l),this.freeString(g,m),this.freeString(f,u),this.freeString(i,p)}}call5(t,n,s,o,r,c){const[l,g]=this.writeString(n),[m,f]=this.writeString(s),[u,i]=this.writeString(o),[p,x]=this.writeString(r),[d,S]=this.writeString(c);try{const B=t(l,g,m,f,u,i,p,x,d,S),W=this.readResult();if(T(this,E).cveil_free_result(),B!==0)throw new X(W||"WASM call failed");return W}finally{this.freeString(l,g),this.freeString(m,f),this.freeString(u,i),this.freeString(p,x),this.freeString(d,S)}}};E=new WeakMap;let he=ke;const ve=k("/api/v1"),Mt=k("/api/v1"),ce=k("/api/v1");let Re=[],De=[],At=[],Pe=[],O=[],A={},R=null,H=null,Je=!1,L=null,le=!1;const Rt="/wasm/cveil_sdk_core.wasm",He={User:{name:"Alice Smith",email:"alice@example.com",ssn:"123-45-6789",age:30,address:{street:"123 Main St",city:"Springfield",zip:"62704"}},Patient:{patientId:"P-78291",firstName:"John",lastName:"Doe",dateOfBirth:"1985-03-14",diagnosis:"Hypertension",insurance:{provider:"BlueCross",policyNumber:"BC-44921-X"}},Order:{orderId:"ORD-10042",customerEmail:"jane@shop.com",phone:"+1-555-867-5309",total:149.99,items:[{sku:"WIDGET-A",name:"Widget Alpha",qty:2}],shipping:{street:"456 Oak Ave",city:"Portland",state:"OR"}}};async function Dt(){var e,t;if(!le)try{L=await he.load(Rt);const n=L.exports;L.call1(n.cveil_init,JSON.stringify({api_base_url:window.location.origin,api_token:"",default_jurisdiction:""}));let s=null,o=!0;for(;o;){const r=s?`/consent/graph?limit=500&after=${encodeURIComponent(s)}`:"/consent/graph?limit=500",c=await ce(r);o=JSON.parse(L.call1(n.cveil_ingest_graph_page,JSON.stringify(c))).hasMore,s=((e=c.pageInfo)==null?void 0:e.endCursor)||((t=c.page_info)==null?void 0:t.end_cursor)||null,s||(o=!1)}le=!0,Ue(!0),v("WASM engine loaded — using real masking SDK",!1),setTimeout(()=>v("",!1),3e3)}catch(n){console.warn("WASM init failed, falling back to JS masking:",n),Ue(!1),v("WASM unavailable — using JS masking fallback",!1),setTimeout(()=>v("",!1),4e3)}}async function Pt(){if(!Je){Je=!0;try{const[e,t,n,s]=await Promise.all([ve("/entities"),Mt("/purposes"),ve("/categories"),ce("/subjects"),Dt()]);Re=e||[],De=t||[],At=n||[],Pe=Array.isArray(s)?s:(s==null?void 0:s.items)||[],we("pg-subject",Pe.map(o=>o.name||o.subjectName||o)),we("pg-entity",Re.map(o=>o.name||o.entityName||o)),we("pg-purpose",De.map(o=>o.name||o.purposeName||o)),Jt()}catch(e){v(`Failed to load reference data: ${e.message}`,!0)}}}function we(e,t){const n=document.getElementById(e);if(!n)return;const s=n.value;n.innerHTML='<option value="">— select —</option>'+t.map(o=>`<option value="${a(String(o))}">${a(String(o))}</option>`).join(""),s&&t.includes(s)&&(n.value=s)}function Jt(){const e=document.getElementById("pg-templates");e&&(e.innerHTML=Object.keys(He).map(t=>`<button onclick="loadSampleTemplate('${t}')"
            class="px-2 py-1 text-xs border border-gray-300 rounded-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900 transition-colors">${a(t)}</button>`).join(""))}function Ht(e){const t=He[e];if(!t)return;const n=document.getElementById("pg-json-input");n&&(n.value=JSON.stringify(t,null,2)),zt()}async function _t(){var l,g,m,f,u;const e=(l=document.getElementById("pg-subject"))==null?void 0:l.value,t=(g=document.getElementById("pg-entity"))==null?void 0:g.value,n=(m=document.getElementById("pg-purpose"))==null?void 0:m.value,s=(u=(f=document.getElementById("pg-json-input"))==null?void 0:f.value)==null?void 0:u.trim();if(!e)return v("Select a subject.",!0);if(!t)return v("Select an entity.",!0);if(!n)return v("Select a purpose.",!0);if(!s)return v("Enter JSON data.",!0);let o;try{o=JSON.parse(s)}catch(i){return v(`Invalid JSON: ${i.message}`,!0)}R=o;const r=Ae(o);if(r.length===0)return v("No leaf fields found in JSON.",!0);v("Evaluating consent…",!1);const c=document.getElementById("pg-evaluate-btn");c&&(c.disabled=!0);try{le?await Ft(e,t,n,o,r):await Gt(e,t,n,o,r),v("",!1)}catch(i){v(`Evaluate failed: ${i.message}`,!0)}finally{c&&(c.disabled=!1)}}async function Ft(e,t,n,s,o){const r=L.exports;let c=L.call4(r.cveil_mask,e,t,JSON.stringify(s),n),l=JSON.parse(c);if(l.needsFetch&&l.needsFetch.length>0&&[...new Set(l.needsFetch.flatMap(u=>u.policyNames||[]))].length>0){const u=o.map(i=>({fieldName:i.split(".").pop(),entityName:t,purposeName:n}));try{const i=await ce(`/subjects/${encodeURIComponent(e)}/evaluate`,{method:"POST",body:JSON.stringify({scopes:u})}),p=Ut(i);p.length>0&&L.call2(r.cveil_ingest_decisions,e,JSON.stringify(p)),c=L.call4(r.cveil_mask,e,t,JSON.stringify(s),n),l=JSON.parse(c)}catch(i){console.warn("Decision fetch failed, using deny-by-default masking:",i)}}H=l.data;const g=L.call4(r.cveil_evaluate,e,t,JSON.stringify(o),n),m=JSON.parse(g);O=o.map((f,u)=>{var d;const i=m[u]||{},p=i.masking_type||i.maskingType||I.RedactAll,x=i.needs_fetch||i.needsFetch||!1;return{path:f,fieldName:i.field||f.split(".").pop(),status:p===I.Plaintext?"GRANTED":"DENIED",resolvedBy:x?"PENDING":(d=i.policy_names)!=null&&d.length?"POLICY":"GRAPH",maskingType:p,detail:i}}),A={},Fe(),Ee(l.data)}function Ut(e){const t=[],n=e.details||e.reasons||{},s=new Set((e.granted||[]).map(o=>o.fieldName||o));for(const[o,r]of Object.entries(n))(r.policyName||r.policy_name)&&t.push({policyName:r.policyName||r.policy_name,action:s.has(o)?"granted":"denied",expiresAt:r.expiresAt||r.expires_at||null});return t}async function Gt(e,t,n,s,o){let r=[];try{r=await ve(`/entities/${encodeURIComponent(t)}/fields`)||[]}catch{}const c={};for(const u of r)c[u.name||u.fieldName]=u.categoryName||u.category||"";const l=o.map(u=>{const i=u.split(".").pop();return{fieldName:i,entityName:t,purposeName:n,category:c[i]||""}}),g=await ce(`/subjects/${encodeURIComponent(e)}/evaluate`,{method:"POST",body:JSON.stringify({scopes:l})}),m=new Set((g.granted||[]).map(u=>u.fieldName||u)),f=g.details||g.reasons||{};O=o.map(u=>{const i=u.split(".").pop(),p=m.has(i),x=f[i]||f[u]||{},d=x.maskingType||(p?I.Plaintext:I.RedactAll);return{path:u,fieldName:i,status:p?"GRANTED":"DENIED",resolvedBy:x.resolvedBy||"NONE",maskingType:d,detail:x}}),A={},Fe(),await _e()}async function _e(){if(!R||O.length===0)return;const e={};for(const n of O)e[n.path]=A[n.path]||n.maskingType;const t=await kt(R,e);H=t,Ee(t)}async function Wt(){var t,n,s;for(const o of O){const r=document.getElementById(`pg-override-${CSS.escape(o.path)}`);r&&r.value&&(A[o.path]=r.value)}const e=Object.keys(A).length>0;if(le&&!e){const o=(t=document.getElementById("pg-subject"))==null?void 0:t.value,r=(n=document.getElementById("pg-entity"))==null?void 0:n.value,c=(s=document.getElementById("pg-purpose"))==null?void 0:s.value;if(o&&r&&c&&R)try{const l=L.exports,g=L.call4(l.cveil_mask,o,r,JSON.stringify(R),c),m=JSON.parse(g);H=m.data,Ee(m.data);return}catch{}}await _e()}function Fe(){const e=document.getElementById("pg-decisions");if(!e||O.length===0)return;const t=O.map(n=>{const s=A[n.path]||n.maskingType,o=n.status==="GRANTED"?'<span class="inline-flex items-center gap-1 text-xs font-medium text-green-400"><span class="w-1.5 h-1.5 rounded-full bg-green-500"></span>GRANTED</span>':'<span class="inline-flex items-center gap-1 text-xs font-medium text-red-400"><span class="w-1.5 h-1.5 rounded-full bg-red-500"></span>DENIED</span>',r=jt.map(c=>`<option value="${c}" ${c===s?"selected":""}>${c}</option>`).join("");return`<tr class="border-t border-gray-700/50">
            <td class="px-3 py-2 font-mono text-xs text-gray-300">${a(n.path)}</td>
            <td class="px-3 py-2">${o}</td>
            <td class="px-3 py-2 text-xs text-gray-400">${a(n.resolvedBy)}</td>
            <td class="px-3 py-2">
                <select id="pg-override-${CSS.escape(n.path)}" onchange="remask()"
                    class="bg-gray-800 border border-gray-600 rounded px-2 py-1 text-xs text-gray-300 focus:border-cv-500 focus:outline-none">
                    ${r}
                </select>
            </td>
        </tr>`}).join("");e.innerHTML=`<table class="w-full text-sm">
        <thead class="text-xs text-gray-500 uppercase">
            <tr>
                <th class="px-3 py-2 text-left">Field Path</th>
                <th class="px-3 py-2 text-left">Status</th>
                <th class="px-3 py-2 text-left">Resolved By</th>
                <th class="px-3 py-2 text-left">Masking Type</th>
            </tr>
        </thead>
        <tbody>${t}</tbody>
    </table>`}function Ee(e){const t=document.getElementById("pg-masked-output");if(!t)return;const n=JSON.stringify(e,null,2),o=JSON.stringify(R,null,2).split(`
`),r=n.split(`
`);let c="";for(let l=0;l<r.length;l++){const g=l<o.length?o[l]:"",m=r[l];g!==m?c+=`<div class="bg-red-900/20 text-red-300">${a(m)}</div>`:c+=`<div class="text-gray-300">${a(m)}</div>`}t.innerHTML=`<div class="flex items-center justify-between mb-2">
        <span class="text-xs text-gray-500 uppercase tracking-wide">Masked Output</span>
        <button onclick="copyMaskedOutput()" class="text-xs text-cv-500 hover:text-cv-400 transition-colors">Copy</button>
    </div>
    <pre class="font-mono text-xs leading-relaxed overflow-auto" style="max-height:340px">${c}</pre>`}function qt(){H&&(navigator.clipboard.writeText(JSON.stringify(H,null,2)),v("Copied to clipboard!",!1),setTimeout(()=>v("",!1),2e3))}function zt(){O=[],A={},R=null,H=null;const e=document.getElementById("pg-decisions"),t=document.getElementById("pg-masked-output");e&&(e.innerHTML='<p class="text-gray-500 text-xs px-3 py-6 text-center">Run an evaluation to see consent decisions.</p>'),t&&(t.innerHTML='<p class="text-gray-500 text-xs px-3 py-6 text-center">Masked output will appear here.</p>')}function Ue(e){const t=document.getElementById("pg-wasm-badge");t&&(e?(t.textContent="WASM SDK",t.className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700"):(t.textContent="JS Fallback",t.className="text-xs px-2 py-0.5 rounded-full bg-amber-100 text-amber-700"))}function v(e,t){const n=document.getElementById("playground-msg");if(n){if(!e){n.className="hidden",n.textContent="";return}n.className=`text-xs px-3 py-2 rounded ${t?"bg-red-900/30 text-red-400":"bg-gray-800 text-gray-400"}`,n.textContent=e}}const Ge="/api/v1/consent/graph",We={purpose:"#14b8a6",regulation:"#3b82f6",jurisdiction:"#f59e0b",field:"#a855f7",subject:"#ec4899"};let qe=!1,ze="force",$={nodes:[],edges:[]},Q=null,Ne=!1,Z=null;const Kt="/api/v1/events/invalidations";let _=null,Ke=!1,F=!1,U=[];const Yt=500,$e={GRANTED:{badge:"bg-green-900/60 text-green-400 border border-green-500/30",dot:"bg-green-500",label:"Granted"},REVOKED:{badge:"bg-red-900/60 text-red-400 border border-red-500/30",dot:"bg-red-500",label:"Revoked"},CONSENT_ENTRY_CHANGED:{badge:"bg-amber-900/60 text-amber-400 border border-amber-500/30",dot:"bg-amber-500",label:"Changed"}},D="/api/v1/subjects";let Ye=[],Ve=!1,M=null;function Xe(e){b(e),e==="graph"&&!qe&&de(),e==="ledger"&&tn(),e==="subjects"&&!Ve&&je(),e==="playground"&&Pt()}function Se(e){return e==="GRANTED"?$e.GRANTED:e==="REVOKED"||e==="ALL_REVOKED"?$e.REVOKED:$e.CONSENT_ENTRY_CHANGED}function Vt(e){ze=e,document.getElementById("graph-mode-force").className="px-3 py-1.5 text-xs font-medium rounded-md transition-colors "+(e==="force"?"bg-cv-500 text-white":"text-gray-400 hover:text-white"),document.getElementById("graph-mode-tree").className="px-3 py-1.5 text-xs font-medium rounded-md transition-colors "+(e==="tree"?"bg-cv-500 text-white":"text-gray-400 hover:text-white"),Te()}async function de(){var s,o;const e=document.getElementById("graph-search").value.trim(),t=document.getElementById("graph-root-type").value,n=new URLSearchParams;n.set("limit","200"),e&&t?(n.set("rootType",t),n.set("rootName",e),n.set("depth","3")):e&&(n.set("rootName",e),n.set("depth","3")),document.getElementById("graph-loading").classList.remove("hidden"),document.getElementById("graph-empty").classList.add("hidden");try{const r=await fetch(`${Ge}?${n}`);if(!r.ok)throw new Error((await r.json().catch(()=>({}))).message||r.statusText);const c=await r.json();if($=c,Q=((s=c.pageInfo)==null?void 0:s.nextCursor)||null,Ne=((o=c.pageInfo)==null?void 0:o.hasMore)||!1,qe=!0,!c.nodes||c.nodes.length===0){document.getElementById("graph-loading").classList.add("hidden"),document.getElementById("graph-empty").classList.remove("hidden"),document.getElementById("graph-pagination").classList.add("hidden");return}Te(),Qe(),document.getElementById("graph-loading").classList.add("hidden")}catch(r){document.getElementById("graph-loading").innerHTML=`<span class="text-red-400 text-sm">${a(r.message)}</span>`}}async function Xt(){var t,n;if(!Q)return;const e=new URLSearchParams;e.set("limit","200"),e.set("cursor",Q);try{const o=await(await fetch(`${Ge}?${e}`)).json(),r=new Set($.nodes.map(l=>l.type+":"+l.name));o.nodes.forEach(l=>{r.has(l.type+":"+l.name)||$.nodes.push(l)});const c=new Set($.edges.map(l=>l.sourceType+":"+l.sourceName+"->"+l.targetType+":"+l.targetName));o.edges.forEach(l=>{c.has(l.sourceType+":"+l.sourceName+"->"+l.targetType+":"+l.targetName)||$.edges.push(l)}),Q=((t=o.pageInfo)==null?void 0:t.nextCursor)||null,Ne=((n=o.pageInfo)==null?void 0:n.hasMore)||!1,Te(),Qe()}catch(s){console.error("Failed to load more graph:",s)}}function Qt(){document.getElementById("graph-search").value="",document.getElementById("graph-root-type").value="",Q=null,de()}function Qe(){const e=document.getElementById("graph-pagination"),t=document.getElementById("graph-stats"),n=$.pageInfo||{};t.textContent=`${$.nodes.length} nodes, ${$.edges.length} edges`+(n.totalNodes?` (of ${n.totalNodes} total)`:""),e.classList.toggle("hidden",!Ne)}function Te(){ze==="force"?Zt():en()}function Zt(){const e=window.d3,t=e.select("#graph-svg");t.selectAll("*").remove();const n=document.getElementById("graph-container"),s=n.clientWidth,o=n.clientHeight;t.attr("viewBox",[0,0,s,o]);const r=new Map;$.nodes.forEach((i,p)=>{r.set(i.type+":"+i.name,p)});const c=$.nodes.map(i=>({id:i.type+":"+i.name,type:i.type,name:i.name,properties:i.properties||{}})),l=$.edges.filter(i=>r.has(i.sourceType+":"+i.sourceName)&&r.has(i.targetType+":"+i.targetName)).map(i=>({source:i.sourceType+":"+i.sourceName,target:i.targetType+":"+i.targetName,properties:i.properties||{}})),g=t.append("g");t.call(e.zoom().scaleExtent([.1,4]).on("zoom",i=>{g.attr("transform",i.transform)}));const m=g.append("g").selectAll("line").data(l).join("line").attr("stroke","#374151").attr("stroke-width",1).attr("stroke-opacity",.6),f=g.append("g").selectAll("circle").data(c).join("circle").attr("r",i=>i.type==="subject"?6:8).attr("fill",i=>We[i.type]||"#6b7280").attr("stroke","#111827").attr("stroke-width",1.5).style("cursor","pointer").call(e.drag().on("start",(i,p)=>{i.active||Z.alphaTarget(.3).restart(),p.fx=p.x,p.fy=p.y}).on("drag",(i,p)=>{p.fx=i.x,p.fy=i.y}).on("end",(i,p)=>{i.active||Z.alphaTarget(0),p.fx=null,p.fy=null})),u=g.append("g").selectAll("text").data(c).join("text").text(i=>i.name.length>20?i.name.slice(0,18)+"…":i.name).attr("font-size",10).attr("fill","#9ca3af").attr("dx",12).attr("dy",4).style("pointer-events","none");f.on("mouseover",(i,p)=>{const x=document.getElementById("graph-tooltip");document.getElementById("tooltip-type").textContent=p.type,document.getElementById("tooltip-name").textContent=p.name;const d=document.getElementById("tooltip-props");d.innerHTML=Object.entries(p.properties).map(([S,B])=>`<div class="flex justify-between gap-4"><span class="text-gray-500">${a(S)}</span><span class="text-white">${a(String(B))}</span></div>`).join(""),x.classList.remove("hidden"),x.style.left=i.pageX+12+"px",x.style.top=i.pageY-10+"px"}).on("mouseout",()=>{document.getElementById("graph-tooltip").classList.add("hidden")}),Z&&Z.stop(),Z=e.forceSimulation(c).force("link",e.forceLink(l).id(i=>i.id).distance(80)).force("charge",e.forceManyBody().strength(-200)).force("center",e.forceCenter(s/2,o/2)).force("collision",e.forceCollide(15)).on("tick",()=>{m.attr("x1",i=>i.source.x).attr("y1",i=>i.source.y).attr("x2",i=>i.target.x).attr("y2",i=>i.target.y),f.attr("cx",i=>i.x).attr("cy",i=>i.y),u.attr("x",i=>i.x).attr("y",i=>i.y)})}function en(){const e=window.d3,t=e.select("#graph-svg");t.selectAll("*").remove();const n=document.getElementById("graph-container"),s=n.clientWidth,o=n.clientHeight;t.attr("viewBox",[0,0,s,o]);const r=["jurisdiction","regulation","purpose","field","subject"],c={name:"Consent Graph",type:"root",children:[]},l={};r.forEach(d=>{l[d]=[]}),$.nodes.forEach(d=>{l[d.type]?l[d.type].push({name:d.name,type:d.type,properties:d.properties||{}}):(l.other||(l.other=[]),l.other.push({name:d.name,type:d.type,properties:d.properties||{}}))});const g=new Map;$.edges.forEach(d=>{const S=d.sourceType+":"+d.sourceName,B=d.targetType+":"+d.targetName;g.has(S)||g.set(S,[]),g.get(S).push(B)});const m=new Set;function f(d){if(m.has(d))return null;m.add(d);const[S,...B]=d.split(":"),ne={name:B.join(":"),type:S,children:[]};return(g.get(d)||[]).forEach(Fn=>{const it=f(Fn);it&&ne.children.push(it)}),ne}if(r.forEach(d=>{var S;(S=l[d])==null||S.forEach(B=>{const W=d+":"+B.name;if(!m.has(W)){const ne=f(W);ne&&c.children.push(ne)}})}),c.children.length===0){document.getElementById("graph-empty").classList.remove("hidden");return}const u=e.hierarchy(c);e.tree().size([o-40,s-200])(u);const p=t.append("g").attr("transform","translate(80, 20)");t.call(e.zoom().scaleExtent([.1,4]).on("zoom",d=>{p.attr("transform",d.transform)})),p.selectAll(".tree-link").data(u.links()).join("path").attr("class","tree-link").attr("d",e.linkHorizontal().x(d=>d.y).y(d=>d.x)).attr("fill","none").attr("stroke","#374151").attr("stroke-width",1.5);const x=p.selectAll(".tree-node").data(u.descendants()).join("g").attr("class","tree-node").attr("transform",d=>`translate(${d.y},${d.x})`);x.append("circle").attr("r",6).attr("fill",d=>We[d.data.type]||"#6b7280").attr("stroke","#111827").attr("stroke-width",1.5),x.append("text").attr("dy",4).attr("x",d=>d.children?-10:10).attr("text-anchor",d=>d.children?"end":"start").attr("font-size",10).attr("fill","#9ca3af").text(d=>d.data.name==="Consent Graph"?"":d.data.name.length>25?d.data.name.slice(0,23)+"…":d.data.name)}function tn(){if(!Ke){Ke=!0;try{_=new EventSource(Kt),_.onopen=()=>{ue("connected")},_.onmessage=e=>{try{const t=JSON.parse(e.data);if(!["GRANTED","REVOKED","ALL_REVOKED","CONSENT_ENTRY_CHANGED"].includes(t.action))return;nn(t)}catch(t){console.warn("Failed to parse SSE event:",t)}},_.onerror=()=>{ue("disconnected"),setTimeout(()=>{_&&_.readyState!==EventSource.CLOSED&&ue("reconnecting")},2e3)}}catch(e){ue("error"),console.error("SSE initialization failed:",e)}}}function ue(e){const t=document.getElementById("sse-status"),n={connected:{dot:"bg-green-500",text:"text-green-400",label:"Live"},disconnected:{dot:"bg-red-500",text:"text-red-400",label:"Disconnected"},reconnecting:{dot:"bg-amber-500 animate-pulse",text:"text-amber-400",label:"Reconnecting…"},error:{dot:"bg-red-500",text:"text-red-400",label:"Error"}},s=n[e]||n.disconnected;t.innerHTML=`<span class="w-2 h-2 rounded-full ${s.dot}"></span><span class="${s.text}">${s.label}</span>`}function nn(e){const t={...e,receivedAt:new Date().toISOString()};U.unshift(t),U.length>Yt&&U.pop(),Ze()}function Ze(){const e=document.getElementById("ledger-feed"),t=document.getElementById("ledger-empty"),n=sn();if(n.length===0){t.classList.remove("hidden"),e.querySelectorAll(".ledger-event").forEach(s=>s.remove()),document.getElementById("ledger-count").textContent="";return}t.classList.add("hidden"),e.innerHTML=n.map(s=>{const o=Se(s.action);return`
            <div class="ledger-event flex items-start gap-4 px-5 py-3 hover:bg-gray-800/30 transition-colors">
                <div class="mt-1.5 flex-shrink-0">
                    <span class="block w-2.5 h-2.5 rounded-full ${o.dot}"></span>
                </div>
                <div class="flex-1 min-w-0">
                    <div class="flex items-center gap-2 mb-1">
                        <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${o.badge}">${o.label}</span>
                        ${s.subject?`<span class="text-xs text-white font-mono truncate">${a(s.subject)}</span>`:""}
                    </div>
                    <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-gray-500">
                        ${s.purpose?`<span>Purpose: <span class="text-gray-300">${a(s.purpose)}</span></span>`:""}
                        ${s.field?`<span>Field: <span class="text-gray-300">${a(s.field)}</span></span>`:""}
                        ${s.entity?`<span>Entity: <span class="text-gray-300">${a(s.entity)}</span></span>`:""}
                        ${s.category?`<span>Category: <span class="text-gray-300">${a(s.category)}</span></span>`:""}
                    </div>
                </div>
                <div class="flex-shrink-0 text-xs text-gray-600 whitespace-nowrap">
                    ${z(s.receivedAt)}
                </div>
            </div>`}).join(""),document.getElementById("ledger-count").textContent=`${n.length} events shown`+(n.length<U.length?` (${U.length} total)`:""),F||(e.scrollTop=0)}function sn(){const e=document.getElementById("ledger-filter-action").value,t=document.getElementById("ledger-filter-subject").value.trim().toLowerCase(),n=document.getElementById("ledger-filter-purpose").value.trim().toLowerCase();return U.filter(s=>!(e&&s.action!==e||t&&(!s.subject||!s.subject.toLowerCase().includes(t))||n&&(!s.purpose||!s.purpose.toLowerCase().includes(n))))}function on(){Ze()}function an(){F=!F;const e=document.getElementById("ledger-pause-btn");e.classList.toggle("text-cv-500",F),e.classList.toggle("border-cv-500/30",F),e.title=F?"Resume auto-scroll":"Pause auto-scroll"}async function je(){try{const e=await fetch(D);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);Ye=await e.json(),Ve=!0,et()}catch(e){document.getElementById("subjects-table").innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-red-400">${a(e.message)}</td></tr>`}}function et(e){const t=document.getElementById("subjects-table");let n=Ye;if(e){const s=e.toLowerCase();n=n.filter(o=>o.subjectName.toLowerCase().includes(s)||(o.description||"").toLowerCase().includes(s))}if(!n.length){t.innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-gray-500">${e?"No matching subjects.":"No subjects registered yet."}</td></tr>`;return}t.innerHTML=n.map(s=>`
        <tr class="hover:bg-gray-800/30 transition-colors cursor-pointer" onclick="viewSubject('${a(s.subjectName)}')">
            <td class="px-6 py-4 font-medium text-white font-mono">${a(s.subjectName)}</td>
            <td class="px-6 py-4 text-gray-400">${a(s.description||"—")}</td>
            <td class="px-6 py-4 text-gray-500 text-xs">${z(s.createdAt)}</td>
            <td class="px-6 py-4 text-right" onclick="event.stopPropagation()">
                <button onclick='openSubjectPanel("edit", ${JSON.stringify(s).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteSubject("${a(s.subjectName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function rn(){et(document.getElementById("subjects-search").value.trim())}async function cn(e){M=e,document.getElementById("subject-detail").classList.remove("hidden"),document.getElementById("subject-detail-title").textContent=e,nt("summary")}function tt(){document.getElementById("subject-detail").classList.add("hidden"),M=null}function nt(e){document.querySelectorAll(".dtab-btn").forEach(t=>{t.classList.remove("border-cv-500","text-cv-500"),t.classList.add("border-transparent","text-gray-400")}),document.getElementById("dtab-"+e).classList.add("border-cv-500","text-cv-500"),document.getElementById("dtab-"+e).classList.remove("border-transparent","text-gray-400"),e==="summary"?ln():e==="history"&&dn()}async function ln(){const e=document.getElementById("subject-detail-body");e.innerHTML='<p class="text-gray-500 text-sm">Loading…</p>';try{const t=await fetch(`${D}/${encodeURIComponent(M)}/summary`);if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);const s=(await t.json()).entries||[];if(!s.length){e.innerHTML='<p class="text-gray-500 text-sm">No active consent entries for this subject.</p>';return}e.innerHTML=`
            <div class="space-y-0 divide-y divide-gray-700/30">
                ${s.map(o=>`
                    <div class="flex items-center gap-4 py-3">
                        <span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${Se(o.action).badge}">${a(o.action)}</span>
                        <div class="flex-1 text-sm">
                            <span class="text-white">${a(o.purposeName)}</span>
                            <span class="text-gray-500 mx-1">→</span>
                            <span class="text-gray-400">${a(o.policyType==="FIELD"?(o.entityName?o.entityName+"/":"")+(o.fieldName||""):o.categoryName||"")}</span>
                        </div>
                        <span class="text-xs text-gray-600">${z(o.createdAt)}</span>
                        ${o.expiresAt?`<span class="text-xs text-amber-500/70">expires ${z(o.expiresAt)}</span>`:""}
                    </div>`).join("")}
            </div>`}catch(t){e.innerHTML=`<p class="text-red-400 text-sm">${a(t.message)}</p>`}}async function dn(){const e=document.getElementById("subject-detail-body");e.innerHTML='<p class="text-gray-500 text-sm">Loading…</p>';try{const t=await fetch(`${D}/${encodeURIComponent(M)}/history`);if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);const s=(await t.json()).entries||[];if(!s.length){e.innerHTML='<p class="text-gray-500 text-sm">No history for this subject.</p>';return}e.innerHTML=`
            <div class="overflow-x-auto">
                <table class="w-full text-xs text-left">
                    <thead class="text-gray-500 uppercase tracking-wide">
                        <tr>
                            <th class="py-2 pr-4">Action</th>
                            <th class="py-2 pr-4">Purpose</th>
                            <th class="py-2 pr-4">Scope</th>
                            <th class="py-2 pr-4">Actor</th>
                            <th class="py-2 pr-4">IP</th>
                            <th class="py-2">Time</th>
                        </tr>
                    </thead>
                    <tbody class="divide-y divide-gray-700/30">
                        ${s.map(o=>`
                            <tr class="hover:bg-gray-800/20">
                                <td class="py-2 pr-4"><span class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium ${Se(o.action).badge}">${a(o.action)}</span></td>
                                <td class="py-2 pr-4 text-white">${a(o.purposeName)}</td>
                                <td class="py-2 pr-4 text-gray-400">${a(o.policyType==="FIELD"?(o.entityName?o.entityName+"/":"")+(o.fieldName||""):o.categoryName||"")}</td>
                                <td class="py-2 pr-4 text-gray-500">${a(o.actor||"—")}</td>
                                <td class="py-2 pr-4 text-gray-600 font-mono">${a(o.ipAddress||"—")}</td>
                                <td class="py-2 text-gray-600">${z(o.createdAt)}</td>
                            </tr>`).join("")}
                    </tbody>
                </table>
            </div>`}catch(t){e.innerHTML=`<p class="text-red-400 text-sm">${a(t.message)}</p>`}}async function un(){if(M)try{const e=await fetch(`${D}/${encodeURIComponent(M)}/export`);if(!e.ok)throw new Error((await e.json().catch(()=>({}))).message||e.statusText);const t=await e.json(),n=new Blob([JSON.stringify(t,null,2)],{type:"application/json"}),s=URL.createObjectURL(n),o=document.createElement("a");o.href=s,o.download=`dsar-export-${M}.json`,o.click(),URL.revokeObjectURL(s)}catch(e){alert("Export failed: "+e.message)}}function pn(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");e==="add"?(n.textContent="Register Subject",s.innerHTML=ot()):e==="edit"&&(n.textContent="Edit Subject",s.innerHTML=ot(t)),me()}function st(){h()}function ot(e){const t=e?a(e.subjectName):"",n=e?a(e.description||""):"";return`
        <form onsubmit="saveSubject(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Subject Name</label>
                    <input id="pf-sub-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-sub-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Register"}
                </button>
                <button type="button" onclick="closeSubjectPanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-sub-msg" class="hidden"></span>
        </form>`}async function gn(e,t,n){e.preventDefault();const s=document.getElementById("pf-sub-name").value.trim(),o=document.getElementById("pf-sub-desc").value.trim(),r=document.getElementById("pf-sub-msg");try{if(t){const c=await fetch(`${D}/${encodeURIComponent(n)}`,{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify({description:o})});if(!c.ok)throw new Error((await c.json().catch(()=>({}))).message||c.statusText)}else{const c=await fetch(D,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({subjectName:s,description:o})});if(!c.ok)throw new Error((await c.json().catch(()=>({}))).message||c.statusText)}st(),je()}catch(c){r.textContent=c.message,r.className="text-sm mt-3 text-red-400",r.classList.remove("hidden")}}function mn(e){C(`Delete subject "${e}"? This cannot be undone.`,async()=>{try{const t=await fetch(`${D}/${encodeURIComponent(e)}`,{method:"DELETE"});if(!t.ok)throw new Error((await t.json().catch(()=>({}))).message||t.statusText);je(),M===e&&tt()}catch(t){alert(t.message)}})}function yn(){Object.assign(window,{showTab:Xe,closeDeleteModal:oe,setGraphMode:Vt,loadGraph:de,resetGraph:Qt,loadMoreGraph:Xt,applyLedgerFilters:on,toggleLedgerPause:an,filterSubjects:rn,openSubjectPanel:pn,closeSubjectPanel:st,viewSubject:cn,closeSubjectDetail:tt,showDetailTab:nt,exportSubjectData:un,saveSubject:gn,deleteSubject:mn,evaluateAndMask:_t,remask:Wt,loadSampleTemplate:Ht,copyMaskedOutput:qt}),ge(),ye(()=>{const e=window.location.hash.replace("#","");["graph","ledger","subjects","playground"].includes(e)?Xe(e):de()})}const fn=Object.freeze(Object.defineProperty({__proto__:null,init:yn},Symbol.toStringTag,{value:"Module"})),w=k("/api/v1/governance"),at="/api/v1/catalogue";let Ie=[],Le=[],pe=[],G=[],P=[];async function xn(){try{const[e,t]=await Promise.all([fetch(at+"/fields").then(n=>n.ok?n.json():[]),fetch(at+"/categories").then(n=>n.ok?n.json():[])]);Ie=e,Le=t}catch(e){console.warn("Failed to load catalogue data:",e)}}function ee(e,t){const n=document.getElementById("panel-title"),s=document.getElementById("panel-body");({"jurisdiction-add":()=>{n.textContent="Add Jurisdiction",s.innerHTML=rt()},"jurisdiction-edit":()=>{n.textContent="Edit Jurisdiction",s.innerHTML=rt(t)},"regulation-add":()=>{n.textContent="Add Regulation",s.innerHTML=En()},"purpose-add":()=>{n.textContent="Add Purpose",s.innerHTML=Ln()},"policy-add":()=>{n.textContent="Add Policy",s.innerHTML=Rn()},"regulation-jurisdictions":()=>{n.textContent="Manage Jurisdictions",s.innerHTML=Sn(t)},"purpose-regulations":()=>{n.textContent="Manage Regulations",s.innerHTML=Cn(t)}}[e]||(()=>{s.innerHTML=""}))(),me()}async function Be(){try{pe=await w("/jurisdictions"),bn()}catch(e){document.getElementById("jurisdictions-table").innerHTML=`<tr><td colspan="3" class="px-6 py-8 text-center text-red-400">${a(e.message)}</td></tr>`}}function bn(){const e=document.getElementById("jurisdictions-table");if(!pe.length){e.innerHTML='<tr><td colspan="3" class="px-6 py-8 text-center text-gray-500">No jurisdictions defined yet.</td></tr>';return}e.innerHTML=pe.map(t=>`
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${a(t.jurisdictionName)}</td>
            <td class="px-6 py-4 text-gray-400">${a(t.description||"—")}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='openPanel("jurisdiction-edit", ${JSON.stringify(t).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-cv-500 mr-3 transition-colors text-xs font-medium">Edit</button>
                <button onclick='deleteJurisdiction("${a(t.jurisdictionName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function rt(e){const t=e?a(e.jurisdictionName):"",n=e?a(e.description||""):"";return`
        <form onsubmit="saveJurisdiction(event, ${e?"true":"false"}, '${t}')">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-jur-name" value="${t}" ${e?"disabled":""} required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none disabled:opacity-50" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-jur-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">${n}</textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    ${e?"Update":"Create"}
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-jur-msg" class="hidden"></span>
        </form>`}async function hn(e,t,n){e.preventDefault();const s=document.getElementById("pf-jur-name").value.trim(),o=document.getElementById("pf-jur-desc").value.trim(),r=document.getElementById("pf-jur-msg");try{t?await w(`/jurisdictions/${encodeURIComponent(n)}`,{method:"PUT",body:JSON.stringify({description:o})}):await w("/jurisdictions",{method:"POST",body:JSON.stringify({jurisdictionName:s,description:o})}),h(),Be()}catch(c){j(r,c.message,!0)}}function vn(e){C(`Delete jurisdiction "${e}"? This cannot be undone.`,async()=>{try{await w(`/jurisdictions/${encodeURIComponent(e)}`,{method:"DELETE"}),Be()}catch(t){alert(t.message)}})}async function te(){try{G=await w("/regulations"),wn()}catch(e){document.getElementById("regulations-table").innerHTML=`<tr><td colspan="4" class="px-6 py-8 text-center text-red-400">${a(e.message)}</td></tr>`}}function wn(){const e=document.getElementById("regulations-table");if(!G.length){e.innerHTML='<tr><td colspan="4" class="px-6 py-8 text-center text-gray-500">No regulations defined yet.</td></tr>';return}e.innerHTML=G.map(t=>`
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${a(t.regulationName)}</td>
            <td class="px-6 py-4 text-gray-400">${a(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.activeJurisdictions||[]).map(n=>q(n.jurisdictionName,"teal")).join("")}
                    <button onclick='openPanel("regulation-jurisdictions", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-600 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deleteRegulation("${a(t.regulationName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function En(){return`
        <form onsubmit="saveRegulation(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-reg-name" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-reg-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-reg-msg" class="hidden"></span>
        </form>`}async function Nn(e){e.preventDefault();const t=document.getElementById("pf-reg-name").value.trim(),n=document.getElementById("pf-reg-desc").value.trim(),s=document.getElementById("pf-reg-msg");try{await w("/regulations",{method:"POST",body:JSON.stringify({regulationName:t,description:n})}),h(),te()}catch(o){j(s,o.message,!0)}}function $n(e){C(`Delete regulation "${e}"? This cannot be undone.`,async()=>{try{await w(`/regulations/${encodeURIComponent(e)}`,{method:"DELETE"}),te()}catch(t){alert(t.message)}})}function Sn(e){const t=new Set((e.activeJurisdictions||[]).map(s=>s.jurisdictionName)),n=pe.filter(s=>!t.has(s.jurisdictionName)).map(s=>`<option value="${a(s.jurisdictionName)}">${a(s.jurisdictionName)}</option>`).join("");return`
        <p class="text-sm text-gray-400 mb-4">Manage jurisdictions for <strong class="text-white">${a(e.regulationName)}</strong></p>
        <div id="rj-list" class="space-y-2 mb-6">
            ${(e.activeJurisdictions||[]).map(s=>`
                <div class="flex items-center justify-between bg-gray-800/60 border border-gray-700 rounded-lg px-3 py-2">
                    <span class="text-sm text-white">${a(s.jurisdictionName)}</span>
                    <button onclick="removeJurisdictionFromRegulation('${a(e.regulationName)}','${a(s.jurisdictionName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Remove</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No jurisdictions linked.</p>'}
        </div>
        <hr class="border-gray-700 mb-4">
        <p class="text-sm text-gray-400 mb-3">Add a jurisdiction</p>
        <form onsubmit="addJurisdictionToRegulation(event, '${a(e.regulationName)}')">
            <div class="flex gap-2">
                <select id="rj-select" class="flex-1 bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                    <option value="">Select…</option>
                    ${n}
                </select>
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Add</button>
            </div>
            <span id="rj-msg" class="hidden"></span>
        </form>`}async function Tn(e,t){e.preventDefault();const s=document.getElementById("rj-select").value;if(s)try{await w("/regulations/jurisdictions",{method:"POST",body:JSON.stringify({regulationName:t,jurisdictionName:s})}),h(),await te();const o=G.find(r=>r.regulationName===t);o&&ee("regulation-jurisdictions",o)}catch(o){j(document.getElementById("rj-msg"),o.message,!0)}}async function jn(e,t){try{await w(`/regulations/${encodeURIComponent(e)}/jurisdictions/${encodeURIComponent(t)}`,{method:"DELETE"}),h(),await te();const n=G.find(s=>s.regulationName===e);n&&ee("regulation-jurisdictions",n)}catch(n){alert(n.message)}}async function J(){try{P=await w("/purposes"),In(),An()}catch(e){document.getElementById("purposes-table").innerHTML=`<tr><td colspan="5" class="px-6 py-8 text-center text-red-400">${a(e.message)}</td></tr>`}}function In(){const e=document.getElementById("purposes-table");if(!P.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No purposes defined yet.</td></tr>';return}e.innerHTML=P.map(t=>`
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${a(t.purposeName)}</td>
            <td class="px-6 py-4 text-gray-400">${a(t.description||"—")}</td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.regulations||[]).map(n=>q(n.regulationName,"blue")).join("")}
                    <button onclick='openPanel("purpose-regulations", ${JSON.stringify(t).replace(/'/g,"&#39;")})'
                        class="inline-flex items-center px-2 py-0.5 rounded text-xs text-gray-500 hover:text-cv-500 border border-dashed border-gray-600 hover:border-cv-500 transition-colors">+ manage</button>
                </div>
            </td>
            <td class="px-6 py-4">
                <div class="flex flex-wrap gap-1">
                    ${(t.policies||[]).map(n=>q(n.policyType==="FIELD"?n.field?n.field.entityName+"/"+n.field.fieldName:"field":n.category?n.category.categoryName:"category","purple")).join("")}
                    <span class="text-xs text-gray-500">${(t.policies||[]).length}</span>
                </div>
            </td>
            <td class="px-6 py-4 text-right">
                <button onclick='deletePurpose("${a(t.purposeName)}")' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Delete</button>
            </td>
        </tr>`).join("")}function Ln(){return`
        <form onsubmit="savePurpose(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Name</label>
                    <input id="pf-pur-name" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Description</label>
                    <textarea id="pf-pur-desc" rows="3"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none"></textarea>
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">
                    Create
                </button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-pur-msg" class="hidden"></span>
        </form>`}async function Bn(e){e.preventDefault();const t=document.getElementById("pf-pur-name").value.trim(),n=document.getElementById("pf-pur-desc").value.trim(),s=document.getElementById("pf-pur-msg");try{await w("/purposes",{method:"POST",body:JSON.stringify({purposeName:t,description:n})}),h(),J()}catch(o){j(s,o.message,!0)}}function kn(e){C(`Delete purpose "${e}"? This will also remove all linked policies and regulations.`,async()=>{try{await w(`/purposes/${encodeURIComponent(e)}`,{method:"DELETE"}),J()}catch(t){alert(t.message)}})}function Cn(e){const t=new Set((e.regulations||[]).map(s=>s.regulationName)),n=G.filter(s=>!t.has(s.regulationName)).map(s=>`<option value="${a(s.regulationName)}">${a(s.regulationName)}</option>`).join("");return`
        <p class="text-sm text-gray-400 mb-4">Manage regulation links for <strong class="text-white">${a(e.purposeName)}</strong></p>
        <div id="pr-list" class="space-y-2 mb-6">
            ${(e.regulations||[]).map(s=>`
                <div class="flex items-center justify-between bg-gray-800/60 border border-gray-700 rounded-lg px-3 py-2">
                    <div>
                        <span class="text-sm text-white">${a(s.regulationName)}</span>
                        ${s.lawfulBasis?`<span class="ml-2 text-xs text-gray-500">basis: ${a(s.lawfulBasis)}</span>`:""}
                    </div>
                    <button onclick="unlinkPurposeRegulation('${a(e.purposeName)}','${a(s.regulationName)}')"
                        class="text-xs text-red-400 hover:text-red-300 transition-colors">Unlink</button>
                </div>`).join("")||'<p class="text-sm text-gray-500">No regulations linked.</p>'}
        </div>
        <hr class="border-gray-700 mb-4">
        <p class="text-sm text-gray-400 mb-3">Link a regulation</p>
        <form onsubmit="linkPurposeRegulation(event, '${a(e.purposeName)}')">
            <div class="space-y-3">
                <select id="pr-select" class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                    <option value="">Select regulation…</option>
                    ${n}
                </select>
                <input id="pr-basis" placeholder="Lawful basis (e.g. consent, legitimate_interest)"
                    class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                <div class="grid grid-cols-2 gap-3">
                    <input id="pr-granted" placeholder="On Granted action"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                    <input id="pr-denied" placeholder="On Denied action"
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none" />
                </div>
                <button type="submit" class="w-full px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm rounded-lg transition-colors">Link</button>
            </div>
            <span id="pr-msg" class="hidden"></span>
        </form>`}async function On(e,t){e.preventDefault();const n=document.getElementById("pr-select").value;if(!n)return;const s=document.getElementById("pr-basis").value.trim()||null,o=document.getElementById("pr-granted").value.trim()||null,r=document.getElementById("pr-denied").value.trim()||null;try{await w("/purposes/regulations",{method:"POST",body:JSON.stringify({purposeName:t,regulationName:n,lawfulBasis:s,onGranted:o,onDenied:r})}),h(),await J();const c=P.find(l=>l.purposeName===t);c&&ee("purpose-regulations",c)}catch(c){j(document.getElementById("pr-msg"),c.message,!0)}}async function Mn(e,t){try{await w(`/purposes/${encodeURIComponent(e)}/regulations/${encodeURIComponent(t)}`,{method:"DELETE"}),h(),await J();const n=P.find(s=>s.purposeName===e);n&&ee("purpose-regulations",n)}catch(n){alert(n.message)}}function An(){const e=document.getElementById("policies-table"),t=[];if(P.forEach(n=>{(n.policies||[]).forEach(s=>{t.push({...s,purposeName:n.purposeName})})}),!t.length){e.innerHTML='<tr><td colspan="5" class="px-6 py-8 text-center text-gray-500">No policies defined yet. Add policies from the Purposes tab.</td></tr>';return}e.innerHTML=t.map(n=>`
        <tr class="hover:bg-gray-800/30 transition-colors">
            <td class="px-6 py-4 font-medium text-white">${a(n.purposeName)}</td>
            <td class="px-6 py-4">${q(n.policyType,n.policyType==="FIELD"?"teal":"purple")}</td>
            <td class="px-6 py-4 text-gray-400">${n.policyType==="FIELD"?a(n.field?n.field.fieldName:"—"):a(n.category?n.category.categoryName:"—")}</td>
            <td class="px-6 py-4 text-gray-400">${n.policyType==="FIELD"&&n.field?a(n.field.entityName):"—"}</td>
            <td class="px-6 py-4 text-right">
                <button onclick='removePolicy("${a(n.purposeName)}", ${JSON.stringify(n).replace(/'/g,"&#39;")})' class="text-gray-400 hover:text-red-400 transition-colors text-xs font-medium">Remove</button>
            </td>
        </tr>`).join("")}function Rn(){const e=Ie.map(n=>{const s=(n.entityName?n.entityName+"/":"")+n.fieldName;return`<option value="${JSON.stringify({fieldName:n.fieldName,entityName:n.entityName||""}).replace(/"/g,"&quot;")}">${a(s)}</option>`}).join(""),t=Le.map(n=>`<option value="${a(n.categoryName)}">${a(n.categoryName)}</option>`).join("");return`
        <form onsubmit="addPolicy(event)">
            <div class="space-y-4">
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Purpose</label>
                    <select id="pf-pol-purpose" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select purpose…</option>
                        ${P.map(n=>`<option value="${a(n.purposeName)}">${a(n.purposeName)}</option>`).join("")}
                    </select>
                </div>
                <div>
                    <label class="block text-sm text-gray-400 mb-1">Policy Type</label>
                    <select id="pf-pol-type" onchange="togglePolicyFields()" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="FIELD">Field</option>
                        <option value="CATEGORY">Category</option>
                    </select>
                </div>
                <div id="pf-pol-field-group">
                    <label class="block text-sm text-gray-400 mb-1">Field</label>
                    <select id="pf-pol-field-select" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select field…</option>
                        ${e}
                    </select>
                    ${Ie.length?"":'<p class="text-xs text-amber-400 mt-1">No fields registered. Add fields in the Catalogue first.</p>'}
                </div>
                <div id="pf-pol-cat-group" class="hidden">
                    <label class="block text-sm text-gray-400 mb-1">Category</label>
                    <select id="pf-pol-cat-select" required
                        class="w-full bg-gray-800 border border-gray-700 rounded-lg px-3 py-2 text-sm text-white focus:border-cv-500 focus:outline-none">
                        <option value="">Select category…</option>
                        ${t}
                    </select>
                    ${Le.length?"":'<p class="text-xs text-amber-400 mt-1">No categories registered. Add categories in the Catalogue first.</p>'}
                </div>
            </div>
            <div class="mt-6 flex gap-3">
                <button type="submit" class="px-4 py-2 bg-cv-500 hover:bg-cv-600 text-white text-sm font-medium rounded-lg transition-colors">Add Policy</button>
                <button type="button" onclick="closePanel()" class="px-4 py-2 text-sm text-gray-400 hover:text-white border border-gray-600 rounded-lg hover:bg-gray-800 transition-colors">Cancel</button>
            </div>
            <span id="pf-pol-msg" class="hidden"></span>
        </form>`}function Dn(){const e=document.getElementById("pf-pol-type").value;document.getElementById("pf-pol-field-group").classList.toggle("hidden",e!=="FIELD"),document.getElementById("pf-pol-cat-group").classList.toggle("hidden",e!=="CATEGORY")}async function Pn(e){e.preventDefault();const t=document.getElementById("pf-pol-purpose").value,n=document.getElementById("pf-pol-type").value,s=document.getElementById("pf-pol-msg"),o={purposeName:t,policyType:n};if(n==="FIELD"){const r=document.getElementById("pf-pol-field-select").value;if(!r){j(s,"Please select a field.",!0);return}const c=JSON.parse(r);o.fieldName=c.fieldName,o.entityName=c.entityName,o.categoryName=null}else{if(o.categoryName=document.getElementById("pf-pol-cat-select").value,!o.categoryName){j(s,"Please select a category.",!0);return}o.fieldName=null,o.entityName=null}try{await w("/purposes/policies",{method:"POST",body:JSON.stringify(o)}),h(),await new Promise(r=>setTimeout(r,800)),await J()}catch(r){j(s,r.message,!0)}}async function Jn(e,t){C(`Remove this ${t.policyType.toLowerCase()} policy from "${e}"?`,async()=>{try{const n={purposeName:e,policyType:t.policyType,fieldName:t.field?t.field.fieldName:null,entityName:t.field?t.field.entityName:null,categoryName:t.category?t.category.categoryName:null};await w("/purposes/policies",{method:"DELETE",body:JSON.stringify(n)}),J()}catch(n){alert(n.message)}})}function Hn(){Object.assign(window,{showTab:b,openPanel:ee,closePanel:h,closeDeleteModal:oe,saveJurisdiction:hn,deleteJurisdiction:vn,saveRegulation:Nn,deleteRegulation:$n,addJurisdictionToRegulation:Tn,removeJurisdictionFromRegulation:jn,savePurpose:Bn,deletePurpose:kn,linkPurposeRegulation:On,unlinkPurposeRegulation:Mn,togglePolicyFields:Dn,addPolicy:Pn,removePolicy:Jn}),ge(),ye(()=>{Be(),te(),J(),xn();const e=window.location.hash.replace("#","");["jurisdictions","regulations","purposes","policies"].includes(e)&&b(e)})}const _n=Object.freeze(Object.defineProperty({__proto__:null,init:Hn},Symbol.toStringTag,{value:"Module"}));return y.catalogue=Tt,y.consent=fn,y.governance=_n,Object.defineProperty(y,Symbol.toStringTag,{value:"Module"}),y})({});
