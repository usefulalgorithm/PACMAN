open Arg
open Cil

let get_int exp =
  match getInteger exp with
    None -> None
  | Some ci ->
    try
      Some (cilint_to_int ci)
    with _ ->
      None

let make_loop = ref false

let max_init_array_size = 1000
  
let initialize_array filename =
  let file = Frontc.parse filename () in
  let declared = Hashtbl.create 10 in
  let add_nondet_decl file fn ty =
    if Hashtbl.mem declared fn then
      ()
    else
      let _ = Hashtbl.add declared fn fn in
      let vi = makeVarinfo true fn (TFun (ty, None, false, [])) in
      let _ = vi.vstorage <- Extern in
      let decl = GVarDecl (vi, locUnknown) in
      let _ = file.globals <- decl::file.globals in
      () in
  let mkNondet lv ty =
    let suffix =
      match ty with
        TInt (IChar, _)
      | TInt (ISChar, _) -> "char"
      | TInt (IUChar, _) -> "uchar"
      | TInt (IInt, _) -> "int"
      | TInt (IUInt, _) -> "uint"
      | TInt (IShort, _) -> "short"
      | TInt (IUShort, _) -> "ushort"
      | TInt (ILong, _) -> "long"
      | TInt (IULong, _) -> "ulong"
      | TFloat (FFloat, _) -> "float"
      | TFloat (FDouble, _) -> "double"
      | _ -> failwith("Unsupport type for nondeterministic values.")  in
    let ftyp = TFun (ty, None, false, []) in
    let fn = "__VERIFIER_nondet_" ^ suffix in
    let vinfo = makeVarinfo true fn ty in
    let lval = Lval (Var vinfo, NoOffset) in
    let instr = Call (Some lv, lval, [], locUnknown) in
    let _ = add_nondet_decl file fn ty in
    instr in
  let rec mkSets vi ty n =
    if n == 0 then
      []
    else
      let lv = (Var vi, Index (integer (n - 1), NoOffset)) in
      (mkNondet lv ty)::(mkSets vi ty (n - 1)) in
  let visitor vmap =
object(self)
  inherit nopCilVisitor as super
  method vvdec vi =
    match vi.vtype with
      TArray (ty, Some size, _) ->
        let _ = Hashtbl.add vmap vi.vname (vi, ty, size) in
        DoChildren
    | _ -> DoChildren
end in
  let mkLoop fd vi ty size =
    let tmp = makeTempVar fd intType in
    let body = [
      mkStmt (Instr [mkNondet (Var vi, Index (Lval (var tmp), NoOffset)) ty])
    ] in
    let loop = mkForIncr tmp zero size one body in
    loop in
  let mkAssigns fd vi ty size = [ mkStmt (Instr (mkSets vi ty size)) ] in
  let _ = iterGlobals file (fun global ->
    match global with
      GFun (fd, _) ->
        let vmap = Hashtbl.create 10 in
        let _ = visitCilFunction (visitor vmap) fd in
        let _ = Hashtbl.iter (fun vn (vi, ty, size) ->
          let stmts =
            if !make_loop then
              mkLoop fd vi ty size
            else
              match get_int size with
                None -> []
              | Some size_n when size_n < max_init_array_size -> mkAssigns fd vi ty size_n
              | _ -> []
          in
          fd.sbody.bstmts <- stmts@fd.sbody.bstmts
        ) vmap in
        ()
    | _ -> ()
  ) in
  dumpFile defaultCilPrinter stdout "" file


let _ = 
  let _ = Errormsg.logChannel := stderr in
  parse [] initialize_array ""
