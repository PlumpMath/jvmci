/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "precompiled.hpp"
#include "memory/oopFactory.hpp"
#include "runtime/javaCalls.hpp"
#include "graal/graalCompiler.hpp"
#include "graal/graalEnv.hpp"
#include "graal/graalRuntime.hpp"
#include "runtime/compilationPolicy.hpp"
#include "runtime/globals_extension.hpp"

GraalCompiler* GraalCompiler::_instance = NULL;

GraalCompiler::GraalCompiler() : AbstractCompiler(graal) {
#ifdef COMPILERGRAAL
  _bootstrapping = false;
#endif
  assert(_instance == NULL, "only one instance allowed");
  _instance = this;
}

// Initialization
void GraalCompiler::initialize() {
#ifdef COMPILERGRAAL
  if (!UseCompiler || !should_perform_init()) {
    return;
  }

  BufferBlob* buffer_blob = GraalRuntime::initialize_buffer_blob();
  if (!UseGraalCompilationQueue) {
    // This path is used for initialization both by the native queue and the graal queue
    // but set_state acquires a lock which might not be safe during JVM_CreateJavaVM, so
    // only update the state flag for the native queue.
    if (buffer_blob == NULL) {
      set_state(failed);
    } else {
      set_state(initialized);
    }
  }

  {
    HandleMark hm;

    _bootstrapping = UseGraalCompilationQueue && (FLAG_IS_DEFAULT(BootstrapGraal) ? !TieredCompilation : BootstrapGraal);

    start_compilation_queue();

    // Graal is considered as application code so we need to
    // stop the VM deferring compilation now.
    CompilationPolicy::completed_vm_startup();

    if (_bootstrapping) {
      // Avoid -Xcomp and -Xbatch problems by turning on interpreter and background compilation for bootstrapping.
      FlagSetting a(UseInterpreter, true);
      FlagSetting b(BackgroundCompilation, true);
#ifndef PRODUCT
      // Turn off CompileTheWorld during bootstrap so that a counter overflow event
      // triggers further compilation (see NonTieredCompPolicy::event()) hence
      // allowing a complete bootstrap
      FlagSetting c(CompileTheWorld, false);
#endif
      bootstrap();
    }

#ifndef PRODUCT
    if (CompileTheWorld) {
      compile_the_world();
    }
#endif
  }
#endif // COMPILERGRAAL
}

#ifdef COMPILERGRAAL
void GraalCompiler::start_compilation_queue() {
  JavaThread* THREAD = JavaThread::current();
  HandleMark hm(THREAD);
  TempNewSymbol name = SymbolTable::new_symbol("com/oracle/graal/hotspot/CompilationQueue", THREAD);
  KlassHandle klass = GraalRuntime::load_required_class(name);
  NoGraalCompilationScheduling ngcs(THREAD);
  klass->initialize(THREAD);
  GUARANTEE_NO_PENDING_EXCEPTION("Error while calling start_compilation_queue");
}


void GraalCompiler::shutdown_compilation_queue() {
  JavaThread* THREAD = JavaThread::current();
  HandleMark hm(THREAD);
  TempNewSymbol name = SymbolTable::new_symbol("com/oracle/graal/hotspot/CompilationQueue", THREAD);
  KlassHandle klass = GraalRuntime::load_required_class(name);
  JavaValue result(T_VOID);
  JavaCallArguments args;
  JavaCalls::call_static(&result, klass, vmSymbols::shutdown_method_name(), vmSymbols::void_method_signature(), &args, THREAD);
  GUARANTEE_NO_PENDING_EXCEPTION("Error while calling shutdown_compilation_queue");
}

void GraalCompiler::bootstrap() {
  JavaThread* THREAD = JavaThread::current();
  TempNewSymbol name = SymbolTable::new_symbol("com/oracle/graal/hotspot/CompilationQueue", THREAD);
  KlassHandle klass = GraalRuntime::load_required_class(name);
  JavaValue result(T_VOID);
  TempNewSymbol bootstrap = SymbolTable::new_symbol("bootstrap", THREAD);
  NoGraalCompilationScheduling ngcs(THREAD);
  JavaCalls::call_static(&result, klass, bootstrap, vmSymbols::void_method_signature(), THREAD);
  GUARANTEE_NO_PENDING_EXCEPTION("Error while calling bootstrap");
}

void GraalCompiler::compile_method(methodHandle method, int entry_bci, CompileTask* task, jboolean blocking) {
  GRAAL_EXCEPTION_CONTEXT

  bool is_osr = entry_bci != InvocationEntryBci;
  if (_bootstrapping && is_osr) {
      // no OSR compilations during bootstrap - the compiler is just too slow at this point,
      // and we know that there are no endless loops
      return;
  }

  ResourceMark rm;
  JavaValue result(T_VOID);
  JavaCallArguments args;
  args.push_long((jlong) (address) method());
  args.push_int(entry_bci);
  args.push_long((jlong) (address) task);
  args.push_int(blocking);
  JavaCalls::call_static(&result, SystemDictionary::CompilationTask_klass(), vmSymbols::compileMetaspaceMethod_name(), vmSymbols::compileMetaspaceMethod_signature(), &args, THREAD);
  GUARANTEE_NO_PENDING_EXCEPTION("Error while calling compile_method");
}


// Compilation entry point for methods
void GraalCompiler::compile_method(ciEnv* env, ciMethod* target, int entry_bci) {
  ShouldNotReachHere();
}

void GraalCompiler::shutdown() {
  shutdown_compilation_queue();
}

// Print compilation timers and statistics
void GraalCompiler::print_timers() {
  TRACE_graal_1("GraalCompiler::print_timers");
}

#endif // COMPILERGRAAL

#ifndef PRODUCT
void GraalCompiler::compile_the_world() {
  // We turn off CompileTheWorld so that Graal can
  // be compiled by C1/C2 when Graal does a CTW.
  CompileTheWorld = false;

  JavaThread* THREAD = JavaThread::current();
  TempNewSymbol name = SymbolTable::new_symbol("com/oracle/graal/hotspot/HotSpotGraalRuntime", THREAD);
  KlassHandle klass = GraalRuntime::load_required_class(name);
  TempNewSymbol compileTheWorld = SymbolTable::new_symbol("compileTheWorld", THREAD);
  JavaValue result(T_VOID);
  JavaCallArguments args;
  args.push_oop(GraalRuntime::get_HotSpotGraalRuntime());
  JavaCalls::call_special(&result, klass, compileTheWorld, vmSymbols::void_method_signature(), &args, THREAD);
  GUARANTEE_NO_PENDING_EXCEPTION("Error while calling compile_the_world");
}
#endif
